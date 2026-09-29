package gov.openfiling.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gov.openfiling.api.dto.AdminCreateUserRequest;
import gov.openfiling.api.dto.AdminUpdateUserRequest;
import gov.openfiling.api.dto.AdminUserResponse;
import gov.openfiling.api.dto.AuditEventResponse;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.RedactionCounts;
import gov.openfiling.api.dto.SystemStatusResponse;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.ConflictException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.mapper.AccountMapperImpl;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.AuditEvent;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.AuditEventRepository;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.RedactionRepository;
import gov.openfiling.api.repository.RefreshTokenRepository;
import gov.openfiling.api.repository.UserRepository;
import gov.openfiling.api.security.Viewer;
import gov.openfiling.api.storage.DocumentStorage;
import gov.openfiling.api.storage.RedactionInvoker;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    UserRepository users;

    @Mock
    RefreshTokenRepository refreshTokens;

    @Mock
    ApplicationRepository applications;

    @Mock
    DocumentRepository documents;

    @Mock
    RedactionRepository redactions;

    @Mock
    AuditEventRepository auditEvents;

    @Mock
    AuthService auth;

    @Mock
    AuditService audit;

    @Mock
    DocumentStorage storage;

    @Mock
    RedactionInvoker invoker;

    @Mock
    RedactionService redactionService;

    AdminService service;
    User adminUser;
    Viewer admin;

    @BeforeEach
    void setUp() {
        service = new AdminService(users, refreshTokens, applications, documents, redactions, auditEvents, auth, new AccountMapperImpl(),
                audit, storage, invoker, redactionService, DocumentServiceTest.PROPS, TestData.CLOCK);
        adminUser = TestData.admin("Root");
        admin = TestData.viewer(adminUser);
    }

    @Test
    @SuppressWarnings("unchecked")
    void users_pageWithApplicationCountsAndLockState() {
        User locked = TestData.inventor("Ada");
        locked.setLockedUntil(TestData.NOW.plusSeconds(60));
        given(users.findAll(any(Specification.class), any(Pageable.class))).willReturn(new PageImpl<>(List.of(locked), PageRequest.of(0, 20), 1));
        given(applications.countByOwners(List.of(locked.getId())))
                .willReturn(List.of(ExaminerAndDashboardServiceTest.idRow(locked.getId(), 4)));

        PageResponse<AdminUserResponse> page = service.users("ada", Role.INVENTOR, PageParams.defaults());

        assertThat(page.content()).singleElement().satisfies(u -> {
            assertThat(u.applicationCount()).isEqualTo(4);
            assertThat(u.locked()).isTrue();
            assertThat(u.roles()).containsExactly("INVENTOR");
        });
    }

    @Test
    void createUser_newEmail_createsWithRolesAndAudits() {
        User created = TestData.examiner("Grace");
        given(users.existsByEmail("grace@openfiling.local")).willReturn(false);
        given(auth.createUser("grace@openfiling.local", "Examiner#12345", "Grace", EnumSet.of(Role.EXAMINER), "2100")).willReturn(created);

        AdminUserResponse response = service.createUser(admin, new AdminCreateUserRequest(" Grace@openfiling.local", " Grace ",
                "Examiner#12345", Set.of(Role.EXAMINER), "2100"));

        assertThat(response.artUnit()).isEqualTo("2100");
        verify(audit).record(eq(AuditAction.ADMIN_USER_CREATED), eq(admin), any(), eq(created.getId()), any());
    }

    @Test
    void createUser_existingEmail_throwsConflict() {
        given(users.existsByEmail("grace@openfiling.local")).willReturn(true);

        assertThatThrownBy(() -> service.createUser(admin, new AdminCreateUserRequest("grace@openfiling.local", "G", "Examiner#12345",
                Set.of(Role.EXAMINER), null))).isInstanceOf(ConflictException.class);
    }

    @Test
    void updateUser_removeOwnAdminOrDisableSelf_throws422WithBothErrors() {
        given(users.findWithRolesById(adminUser.getId())).willReturn(Optional.of(adminUser));

        assertThatThrownBy(() -> service.updateUser(admin, adminUser.getId(),
                new AdminUpdateUserRequest(Set.of(Role.EXAMINER), false, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrors()).hasSize(2));
    }

    @Test
    void updateUser_grantRolesArtUnitUnlockAndDisable_appliesAuditsAndRevokesSessions() {
        User target = TestData.inventor("Ada");
        target.setLockedUntil(TestData.NOW.plusSeconds(600));
        target.setFailedLogins(5);
        given(users.findWithRolesById(target.getId())).willReturn(Optional.of(target));
        given(applications.countByOwners(List.of(target.getId()))).willReturn(List.of());

        AdminUserResponse response = service.updateUser(admin, target.getId(),
                new AdminUpdateUserRequest(Set.of(Role.INVENTOR, Role.EXAMINER), false, "3600", true));

        assertThat(response.roles()).containsExactly("INVENTOR", "EXAMINER");
        assertThat(response.enabled()).isFalse();
        assertThat(response.locked()).isFalse();
        assertThat(response.artUnit()).isEqualTo("3600");
        assertThat(target.getFailedLogins()).isZero();
        verify(refreshTokens).revokeAllOfUser(target.getId(), TestData.NOW);
        verify(audit).record(eq(AuditAction.ADMIN_USER_UPDATED), eq(admin), any(), eq(target.getId()),
                org.mockito.ArgumentMatchers.<Map<String, ?>>argThat(m -> m.containsKey("roles") && m.containsKey("enabled")
                        && m.containsKey("artUnit") && m.containsKey("unlocked")));
    }

    @Test
    void updateUser_noEffectiveChange_doesNotAudit() {
        User target = TestData.examiner("Grace");
        given(users.findWithRolesById(target.getId())).willReturn(Optional.of(target));

        service.updateUser(admin, target.getId(), new AdminUpdateUserRequest(Set.of(Role.EXAMINER), true, "2100", false));

        verify(audit, never()).record(any(), any(Viewer.class), any(), any(), any());
        verify(refreshTokens, never()).revokeAllOfUser(any(), any());
    }

    @Test
    void updateUser_clearArtUnit_withEmptyString() {
        User target = TestData.examiner("Grace");
        given(users.findWithRolesById(target.getId())).willReturn(Optional.of(target));

        assertThat(service.updateUser(admin, target.getId(), new AdminUpdateUserRequest(null, null, "", null)).artUnit()).isNull();
    }

    @Test
    void updateUser_unknown_throwsNotFound() {
        UUID id = UUID.randomUUID();
        given(users.findWithRolesById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateUser(admin, id, new AdminUpdateUserRequest(null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void audit_mapsDetailsJson() {
        AuditEvent e = new AuditEvent();
        e.setId(7L);
        e.setOccurredAt(TestData.NOW);
        e.setAction(AuditAction.WORKFLOW_ACTION);
        e.setDetails("{\"action\":\"CLAIM\"}");
        given(auditEvents.findAll(any(Specification.class), any(Pageable.class))).willReturn(new PageImpl<>(List.of(e)));

        PageResponse<AuditEventResponse> page = service.audit(adminUser.getId().toString(), AuditAction.WORKFLOW_ACTION, "APPLICATION",
                "x", TestData.NOW.minusSeconds(60), TestData.NOW, PageParams.defaults());

        assertThat(page.content().getFirst().details().get("action").asString()).isEqualTo("CLAIM");
    }

    @Test
    void system_allUp_reportsComponentsCountsAndCategories() {
        given(users.count()).willReturn(3L);
        given(storage.bucketHealth(any())).willReturn(new DocumentStorage.BucketHealth(true, "ok"));
        given(invoker.health()).willReturn(new RedactionInvoker.Health(true, "status UP"));
        given(applications.count()).willReturn(13L);
        given(applications.countByStatusNot(any())).willReturn(11L);
        given(documents.countByStatusNot(any())).willReturn(4L);
        given(documents.sumUploadedBytes()).willReturn(4096L);
        given(redactionService.counts()).willReturn(new RedactionCounts(0, 1, 2, 0));
        given(redactions.sumCompletedRedactions()).willReturn(9L);
        given(redactions.totalsByCategory()).willReturn(List.of(new RedactionRepository.CategoryTotalRow() {
            @Override
            public String getCategory() {
                return "SSN";
            }

            @Override
            public long getTotal() {
                return 9;
            }
        }));

        SystemStatusResponse status = service.system();

        assertThat(status.components()).extracting(SystemStatusResponse.Component::status).containsExactly("UP", "UP", "UP");
        assertThat(status.counts()).isEqualTo(new SystemStatusResponse.Counts(3, 13, 11, 4, 4096));
        assertThat(status.redactions().totalRedactions()).isEqualTo(9);
        assertThat(status.redactions().byCategory()).singleElement().satisfies(c -> assertThat(c.category()).isEqualTo("SSN"));
    }

    @Test
    void system_databaseAndDependenciesDown_reportsDownWithZeroCounts() {
        given(users.count()).willThrow(new DataAccessResourceFailureException("down"));
        given(storage.bucketHealth(any())).willReturn(new DocumentStorage.BucketHealth(false, "unreachable"));
        given(invoker.health()).willReturn(new RedactionInvoker.Health(false, "Unreachable"));

        SystemStatusResponse status = service.system();

        assertThat(status.components()).extracting(SystemStatusResponse.Component::status).containsExactly("DOWN", "DOWN", "DOWN");
        assertThat(status.counts().users()).isZero();
    }
}
