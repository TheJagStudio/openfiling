package gov.openfiling.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gov.openfiling.api.dto.ChangePasswordRequest;
import gov.openfiling.api.dto.SessionResponse;
import gov.openfiling.api.dto.UpdateProfileRequest;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.mapper.AccountMapperImpl;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.RefreshToken;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.RefreshTokenRepository;
import gov.openfiling.api.repository.UserRepository;
import gov.openfiling.api.security.Viewer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    UserRepository users;

    @Mock
    RefreshTokenRepository refreshTokens;

    @Mock
    PasswordEncoder encoder;

    @Mock
    AuditService audit;

    AccountService service;
    User user;
    Viewer viewer;

    @BeforeEach
    void setUp() {
        service = new AccountService(users, refreshTokens, encoder, new AccountMapperImpl(), audit, TestData.CLOCK);
        user = TestData.inventor("Ada");
        viewer = TestData.viewer(user);
    }

    @Test
    void updateProfile_trimsDisplayName() {
        given(users.findWithRolesById(user.getId())).willReturn(Optional.of(user));
        given(users.saveAndFlush(user)).willReturn(user);

        assertThat(service.updateProfile(viewer, new UpdateProfileRequest("  Ada Lovelace ")).displayName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void updateProfile_unknownUser_throwsNotFound() {
        given(users.findWithRolesById(user.getId())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProfile(viewer, new UpdateProfileRequest("X"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void changePassword_correctCurrent_storesHashRevokesSessionsAndAudits() {
        given(users.findWithRolesById(user.getId())).willReturn(Optional.of(user));
        given(encoder.matches("old-password-1", user.getPasswordHash())).willReturn(true);
        given(encoder.encode("new-password-2")).willReturn("{bcrypt}new");
        given(refreshTokens.revokeAllOfUser(user.getId(), TestData.NOW)).willReturn(3);

        service.changePassword(viewer, new ChangePasswordRequest("old-password-1", "new-password-2"));

        assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}new");
        verify(refreshTokens).revokeAllOfUser(user.getId(), TestData.NOW);
        verify(audit).record(eq(AuditAction.PASSWORD_CHANGED), eq(viewer), any(), any(), any());
    }

    @Test
    void changePassword_wrongCurrent_throws422OnCurrentPassword() {
        given(users.findWithRolesById(user.getId())).willReturn(Optional.of(user));
        given(encoder.matches("wrong", user.getPasswordHash())).willReturn(false);

        assertThatThrownBy(() -> service.changePassword(viewer, new ChangePasswordRequest("wrong", "new-password-2")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrors().getFirst().field()).isEqualTo("currentPassword"));
        verify(refreshTokens, never()).revokeAllOfUser(any(), any());
    }

    @Test
    void changePassword_sameAsCurrent_throws422() {
        given(users.findWithRolesById(user.getId())).willReturn(Optional.of(user));
        given(encoder.matches("same-password-1", user.getPasswordHash())).willReturn(true);

        assertThatThrownBy(() -> service.changePassword(viewer, new ChangePasswordRequest("same-password-1", "same-password-1")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void sessions_activeHeads_groupedByFamilyWithStartTimes() {
        UUID family = UUID.randomUUID();
        RefreshToken head = new RefreshToken();
        head.setId(UUID.randomUUID());
        head.setFamilyId(family);
        head.setCreatedAt(TestData.NOW.minus(Duration.ofMinutes(5)));
        head.setExpiresAt(TestData.NOW.plus(Duration.ofDays(7)));
        head.setUserAgent("Firefox");
        head.setIp("10.0.0.1");
        given(refreshTokens.findActiveByUser(user.getId(), TestData.NOW)).willReturn(List.of(head));
        Instant started = TestData.NOW.minus(Duration.ofDays(2));
        given(refreshTokens.familyStarts(any())).willReturn(List.of(new RefreshTokenRepository.FamilyStartRow() {
            @Override
            public UUID getId() {
                return family;
            }

            @Override
            public Instant getStartedAt() {
                return started;
            }
        }));

        List<SessionResponse> sessions = service.sessions(viewer);

        assertThat(sessions).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo(family);
            assertThat(s.createdAt()).isEqualTo(started);
            assertThat(s.lastUsedAt()).isEqualTo(head.getCreatedAt());
            assertThat(s.userAgent()).isEqualTo("Firefox");
            assertThat(s.ip()).isEqualTo("10.0.0.1");
        });
    }

    @Test
    void sessions_none_returnsEmpty() {
        given(refreshTokens.findActiveByUser(user.getId(), TestData.NOW)).willReturn(List.of());

        assertThat(service.sessions(viewer)).isEmpty();
    }

    @Test
    void revokeSession_ownActiveFamily_revokesAndAudits() {
        UUID family = UUID.randomUUID();
        given(refreshTokens.revokeFamilyOfUser(user.getId(), family, TestData.NOW)).willReturn(1);

        service.revokeSession(viewer, family);

        verify(audit).record(eq(AuditAction.AUTH_LOGOUT), eq(viewer), eq("SESSION"), eq(family), any());
    }

    @Test
    void revokeSession_foreignOrUnknownFamily_throwsNotFound() {
        UUID family = UUID.randomUUID();
        given(refreshTokens.revokeFamilyOfUser(user.getId(), family, TestData.NOW)).willReturn(0);
        given(refreshTokens.existsByFamilyIdAndUser_Id(family, user.getId())).willReturn(false);

        assertThatThrownBy(() -> service.revokeSession(viewer, family)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void revokeSession_alreadyRevoked_isIdempotent() {
        UUID family = UUID.randomUUID();
        given(refreshTokens.revokeFamilyOfUser(user.getId(), family, TestData.NOW)).willReturn(0);
        given(refreshTokens.existsByFamilyIdAndUser_Id(family, user.getId())).willReturn(true);

        service.revokeSession(viewer, family);

        verify(audit, never()).record(any(), any(Viewer.class), any(), any(), any());
    }

    @Test
    void revokeAll_revokesEveryTokenAndAudits() {
        service.revokeAll(viewer);

        verify(refreshTokens).revokeAllOfUser(user.getId(), TestData.NOW);
        verify(audit).record(eq(AuditAction.AUTH_LOGOUT), eq(viewer), any(), any(), any());
    }
}
