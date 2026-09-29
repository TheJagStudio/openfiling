package gov.openfiling.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.dto.NotificationResponse;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.ReferenceResponse;
import gov.openfiling.api.dto.StatusEventResponse;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.AuditEvent;
import gov.openfiling.api.model.Notification;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.RefreshToken;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.model.StatusEvent;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.AuditEventRepository;
import gov.openfiling.api.repository.NotificationRepository;
import gov.openfiling.api.repository.RefreshTokenRepository;
import gov.openfiling.api.repository.StatusEventRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import gov.openfiling.api.security.JwtKeyProvider;
import gov.openfiling.api.security.Viewer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Smaller services and helpers: notifications, history, audit, tokens, reference data, numbers. */
@ExtendWith(MockitoExtension.class)
class SupportServicesTest {

    @Mock
    NotificationRepository notificationRepository;

    @Mock
    StatusEventRepository statusEvents;

    @Mock
    ApplicationRepository applications;

    @Mock
    AuditEventRepository auditEvents;

    @Mock
    RefreshTokenRepository refreshTokens;

    // ---- notifications -----------------------------------------------------------------------

    @Test
    void notify_savesTruncatedNotification() {
        NotificationService service = new NotificationService(notificationRepository, TestData.CLOCK);
        PatentApplication app = TestData.application(TestData.inventor("Ada"), ApplicationStatus.SUBMITTED);
        UUID userId = UUID.randomUUID();

        service.notify(userId, app, "SUBMIT", "t".repeat(300), "message");

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getTitle()).hasSize(200).endsWith("…");
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(TestData.NOW);
    }

    @Test
    void notify_nullRecipient_isNoOp() {
        new NotificationService(notificationRepository, TestData.CLOCK).notify(null, null, "X", "t", "m");

        org.mockito.Mockito.verifyNoInteractions(notificationRepository);
    }

    @Test
    void list_unreadOnly_mapsApplicationNumber() {
        NotificationService service = new NotificationService(notificationRepository, TestData.CLOCK);
        User user = TestData.inventor("Ada");
        Notification n = new Notification();
        n.setId(UUID.randomUUID());
        n.setType("CLAIM");
        n.setTitle("t");
        n.setMessage("m");
        n.setApplication(TestData.application(user, ApplicationStatus.DOCKETED));
        n.setCreatedAt(TestData.NOW);
        given(notificationRepository.findByUserIdAndReadAtIsNull(eq(user.getId()), any(Pageable.class))).willReturn(new PageImpl<>(List.of(n)));

        PageResponse<NotificationResponse> page = service.list(TestData.viewer(user), true, PageParams.defaults());

        assertThat(page.content().getFirst().applicationNumber()).isEqualTo("19000123");
        assertThat(page.content().getFirst().read()).isFalse();
    }

    @Test
    void list_all_usesUnfilteredQuery() {
        NotificationService service = new NotificationService(notificationRepository, TestData.CLOCK);
        User user = TestData.inventor("Ada");
        given(notificationRepository.findByUserId(eq(user.getId()), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));

        assertThat(service.list(TestData.viewer(user), false, PageParams.defaults()).content()).isEmpty();
    }

    @Test
    void markRead_ownNotification_setsReadAt_otherwise404() {
        NotificationService service = new NotificationService(notificationRepository, TestData.CLOCK);
        User user = TestData.inventor("Ada");
        Notification n = new Notification();
        UUID id = UUID.randomUUID();
        given(notificationRepository.findByIdAndUserId(id, user.getId())).willReturn(Optional.of(n));
        UUID foreign = UUID.randomUUID();
        given(notificationRepository.findByIdAndUserId(foreign, user.getId())).willReturn(Optional.empty());

        service.markRead(TestData.viewer(user), id);

        assertThat(n.getReadAt()).isEqualTo(TestData.NOW);
        assertThatThrownBy(() -> service.markRead(TestData.viewer(user), foreign)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void unreadCountAndMarkAllRead_delegateToRepository() {
        NotificationService service = new NotificationService(notificationRepository, TestData.CLOCK);
        Viewer viewer = TestData.viewer(TestData.inventor("Ada"));
        given(notificationRepository.countByUserIdAndReadAtIsNull(viewer.id())).willReturn(5L);

        assertThat(service.unreadCount(viewer).count()).isEqualTo(5);
        service.markAllRead(viewer);
        verify(notificationRepository).markAllRead(viewer.id(), TestData.NOW);
    }

    @Test
    void label_usesDisplayNumberOrQuotedTitle() {
        PatentApplication filed = TestData.application(TestData.inventor("A"), ApplicationStatus.SUBMITTED);
        PatentApplication draft = TestData.application(TestData.inventor("A"), ApplicationStatus.DRAFT);

        assertThat(NotificationService.label(filed)).isEqualTo("19/000,123");
        assertThat(NotificationService.label(draft)).startsWith("\"Solid-state");
    }

    // ---- history ----------------------------------------------------------------------------------

    @Test
    void history_visibleApplication_mapsActorsIncludingSystem() {
        User owner = TestData.inventor("Ada");
        PatentApplication app = TestData.application(owner, ApplicationStatus.SUBMITTED);
        given(applications.findWithPartiesById(app.getId())).willReturn(Optional.of(app));
        StatusEvent byOwner = new StatusEvent();
        byOwner.setId(1L);
        byOwner.setEventCode("SUBMIT");
        byOwner.setActor(owner);
        byOwner.setActorRole(HistoryService.ROLE_INVENTOR);
        StatusEvent bySystem = new StatusEvent();
        bySystem.setEventCode("EXPIRED");
        given(statusEvents.findByApplicationIdOrderByCreatedAtAscIdAsc(app.getId())).willReturn(List.of(byOwner, bySystem));
        HistoryService service = new HistoryService(statusEvents, new ApplicationAccess(applications, new WorkflowPolicy()), TestData.CLOCK);

        List<StatusEventResponse> history = service.history(TestData.viewer(owner), app.getId());

        assertThat(history.get(0).actor()).isEqualTo(new StatusEventResponse.Actor("Ada", "INVENTOR"));
        assertThat(history.get(1).actor().role()).isEqualTo("SYSTEM");
        assertThat(history.get(1).id()).isZero();
    }

    @Test
    void record_trimsNoteAndTruncatesDescription() {
        HistoryService service = new HistoryService(statusEvents, null, TestData.CLOCK);
        given(statusEvents.save(any(StatusEvent.class))).willAnswer(inv -> inv.getArgument(0));
        PatentApplication app = TestData.application(TestData.inventor("Ada"), ApplicationStatus.DRAFT);

        StatusEvent e = service.record(app, "X", null, null, "d".repeat(400), "  note ", null, "SYSTEM");

        assertThat(e.getDescription()).hasSize(300);
        assertThat(e.getNote()).isEqualTo("note");
        assertThat(e.getCreatedAt()).isEqualTo(TestData.NOW);
        assertThat(service.record(app, "X", null, null, "d", " ", null, "SYSTEM").getNote()).isNull();
    }

    @Test
    void examinerRole_adminWithoutExaminerRole_isAdmin() {
        assertThat(HistoryService.examinerRole(Viewer.of(UUID.randomUUID(), "a@b", Role.ADMIN))).isEqualTo("ADMIN");
        assertThat(HistoryService.examinerRole(Viewer.of(UUID.randomUUID(), "a@b", Role.ADMIN, Role.EXAMINER))).isEqualTo("EXAMINER");
    }

    // ---- audit ------------------------------------------------------------------------------------

    @Test
    void audit_recordsActorResourceAndJsonDetails() {
        AuditService service = new AuditService(auditEvents, TestData.CLOCK);
        Viewer viewer = TestData.viewer(TestData.inventor("Ada"));

        service.record(AuditAction.APPLICATION_CREATED, viewer, "APPLICATION", UUID.randomUUID(),
                AuditService.details("title", "T", "skipped", null));
        service.recordIndependent(AuditAction.AUTH_LOGIN_FAILED, null, "x".repeat(300), "USER", null, null);
        service.record(AuditAction.REDACTION_COMPLETED, (Viewer) null, "DOCUMENT", "id", null);

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents, org.mockito.Mockito.times(3)).save(saved.capture());
        AuditEvent first = saved.getAllValues().get(0);
        assertThat(first.getActorId()).isEqualTo(viewer.id());
        assertThat(first.getDetails()).isEqualTo("{\"title\":\"T\"}");
        assertThat(first.getOccurredAt()).isEqualTo(TestData.NOW);
        assertThat(saved.getAllValues().get(1).getActorEmail()).hasSize(254);
        assertThat(saved.getAllValues().get(1).getDetails()).isEqualTo("{}");
        assertThat(saved.getAllValues().get(2).getActorEmail()).isEqualTo(AuditService.SYSTEM);
    }

    // ---- tokens -----------------------------------------------------------------------------------

    @Test
    void tokenService_issuesRs256TokenWithRolesAndEmail(@TempDir Path keys) {
        AppProperties.Jwt props = new AppProperties.Jwt("openfiling", Duration.ofMinutes(15), Duration.ofDays(7), keys.toString());
        JwtKeyProvider keyProvider = new JwtKeyProvider(props);
        TokenService tokens = new TokenService(
                new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keyProvider.signingKey()))), props, java.time.Clock.systemUTC());
        User user = TestData.user("Grace", Role.EXAMINER, Role.ADMIN);

        TokenService.IssuedToken issued = tokens.issue(user);
        JwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keyProvider.publicKey()).build();
        Jwt jwt = decoder.decode(issued.value());

        assertThat(issued.expiresInSeconds()).isEqualTo(900);
        assertThat(issued.toString()).doesNotContain(issued.value());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("EXAMINER", "ADMIN");
        assertThat(jwt.getClaimAsString("email")).isEqualTo(user.getEmail());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("openfiling");
    }

    @Test
    void refreshTokenService_issuesOpaqueTokenStoringOnlyItsHash() {
        AppProperties.Jwt props = new AppProperties.Jwt("openfiling", Duration.ofMinutes(15), Duration.ofDays(7), "x");
        RefreshTokenService service = new RefreshTokenService(refreshTokens, props, TestData.CLOCK);
        User user = TestData.inventor("Ada");
        UUID family = UUID.randomUUID();

        RefreshTokenService.Issued issued = service.issue(user, family);

        assertThat(issued.rawToken()).hasSize(43).matches("[A-Za-z0-9_-]+");
        RefreshToken token = issued.token();
        assertThat(token.getTokenHash()).isEqualTo(RefreshTokenService.hash(issued.rawToken())).hasSize(64);
        assertThat(token.getTokenHash()).isNotEqualTo(issued.rawToken());
        assertThat(token.getFamilyId()).isEqualTo(family);
        assertThat(token.getExpiresAt()).isEqualTo(TestData.NOW.plus(Duration.ofDays(7)));
        assertThat(token.isActiveAt(TestData.NOW)).isTrue();
        assertThat(issued.toString()).doesNotContain(issued.rawToken());
        assertThat(service.ttlSeconds()).isEqualTo(604800);
        verify(refreshTokens).save(token);
    }

    // ---- reference / numbers / after-commit ---------------------------------------------------

    @Test
    void reference_listsEnumsAndLimits() {
        ReferenceResponse ref = new ReferenceService(DocumentServiceTest.PROPS).reference();

        assertThat(ref.statuses()).hasSize(12);
        assertThat(ref.statuses().getLast().terminal()).isTrue();
        assertThat(ref.documentCodes()).hasSize(15);
        assertThat(ref.applicationTypes()).extracting(ReferenceResponse.CodeLabel::code)
                .containsExactly("UTILITY", "DESIGN", "PLANT", "PROVISIONAL");
        assertThat(ref.limits().allowedContentTypes()).containsExactly("application/pdf");
        assertThat(ref.limits().maxDocumentsPerApplication()).isEqualTo(3);
    }

    @Test
    void applicationNumbers_formatsAndNormalizes() {
        assertThat(ApplicationNumbers.applicationNumber(ApplicationType.PROVISIONAL, 42)).isEqualTo("63000042");
        assertThat(ApplicationNumbers.display("19000123")).isEqualTo("19/000,123");
        assertThat(ApplicationNumbers.display(null)).isNull();
        assertThat(ApplicationNumbers.display("123")).isEqualTo("123");
        assertThat(ApplicationNumbers.normalize("19/000,123")).contains("19000123");
        assertThat(ApplicationNumbers.normalize(" 19000123 ")).contains("19000123");
        assertThat(ApplicationNumbers.normalize("1900012")).isEmpty();
        assertThat(ApplicationNumbers.normalize(null)).isEmpty();
        assertThat(ApplicationNumbers.publicationNumber(LocalDate.of(2026, 3, 1), 12345)).isEqualTo("US20260012345A1");
        assertThat(ApplicationNumbers.patentNumber(12500001, false)).isEqualTo("US12500001B1");
        assertThat(ApplicationNumbers.patentNumber(12500001, true)).isEqualTo("US12500001B2");
    }

    @Test
    void afterCommit_withoutTransaction_runsImmediatelyAndSwallowsFailures() {
        AtomicBoolean ran = new AtomicBoolean();

        AfterCommit.run("test", () -> ran.set(true));
        AfterCommit.run("boom", () -> {
            throw new IllegalStateException("x");
        });

        assertThat(ran).isTrue();
    }

    @Test
    void afterCommit_insideSynchronization_defersUntilCommit() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            AtomicBoolean ran = new AtomicBoolean();
            AfterCommit.run("deferred", () -> ran.set(true));
            assertThat(ran).isFalse();
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            assertThat(ran).isTrue();
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
