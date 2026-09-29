package gov.openfiling.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.dto.AuthResponse;
import gov.openfiling.api.dto.LoginRequest;
import gov.openfiling.api.dto.RegisterRequest;
import gov.openfiling.api.exception.AccountLockedException;
import gov.openfiling.api.exception.ConflictException;
import gov.openfiling.api.exception.InvalidCredentialsException;
import gov.openfiling.api.exception.InvalidRefreshTokenException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.mapper.AccountMapperImpl;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.RefreshToken;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.RefreshTokenRepository;
import gov.openfiling.api.repository.UserRepository;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository users;

    @Mock
    RefreshTokenRepository refreshTokens;

    @Mock
    RefreshTokenService refreshTokenService;

    @Mock
    PasswordEncoder encoder;

    @Mock
    TokenService tokens;

    @Mock
    AuditService audit;

    AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, refreshTokens, refreshTokenService, encoder, tokens, new AccountMapperImpl(), audit,
                new AppProperties.Security("t", 5, Duration.ofMinutes(15), 20), TestData.CLOCK);
    }

    private void stubTokenIssuing(User user) {
        given(tokens.issue(any())).willReturn(new TokenService.IssuedToken("jwt", 900));
        RefreshToken rt = new RefreshToken();
        rt.setId(UUID.randomUUID());
        given(refreshTokenService.issue(any(), any())).willReturn(new RefreshTokenService.Issued("refresh-raw", rt));
        given(refreshTokenService.ttlSeconds()).willReturn(604800L);
    }

    @Test
    void register_newEmail_createsInventorAndIssuesBothTokens() {
        given(users.existsByEmail("ada@example.com")).willReturn(false);
        given(encoder.encode("secret-pass-1")).willReturn("{bcrypt}hash");
        given(users.saveAndFlush(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        stubTokenIssuing(null);

        AuthResponse response = service.register(new RegisterRequest("  Ada@Example.com ", "secret-pass-1", " Ada "));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ada@example.com");
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Ada");
        assertThat(saved.getValue().getRoles()).containsExactly(Role.INVENTOR);
        assertThat(response.accessToken()).isEqualTo("jwt");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.refreshToken()).isEqualTo("refresh-raw");
        assertThat(response.refreshExpiresIn()).isEqualTo(604800);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.user().roles()).containsExactly("INVENTOR");
        verify(audit).record(eq(AuditAction.USER_REGISTERED), any(UUID.class), eq("ada@example.com"), anyString(), any(), isNull());
    }

    @Test
    void register_existingEmail_throwsConflict() {
        given(users.existsByEmail("ada@example.com")).willReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest("ada@example.com", "secret-pass-1", "Ada")))
                .isInstanceOf(ConflictException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void createUser_concurrentDuplicate_throwsConflict() {
        given(encoder.encode(anyString())).willReturn("h");
        given(users.saveAndFlush(any(User.class))).willThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatThrownBy(() -> service.createUser("a@b.co", "pw", "A", EnumSet.of(Role.INVENTOR), " "))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void login_correctPassword_recordsSuccessAuditsAndIssuesTokens() {
        User u = TestData.inventor("Ada");
        given(users.findByEmail(u.getEmail())).willReturn(Optional.of(u));
        given(encoder.matches("pw-123456789", u.getPasswordHash())).willReturn(true);
        stubTokenIssuing(u);

        AuthResponse response = service.login(new LoginRequest(u.getEmail().toUpperCase(), "pw-123456789"));

        assertThat(response.accessToken()).isEqualTo("jwt");
        assertThat(response.user().lastLoginAt()).isEqualTo(TestData.NOW);
        verify(users).recordSuccessfulLogin(u.getId(), TestData.NOW);
        verify(audit).record(eq(AuditAction.AUTH_LOGIN_SUCCESS), eq(u.getId()), eq(u.getEmail()), anyString(), any(), isNull());
    }

    @Test
    void login_unknownEmail_throwsInvalidAfterDummyHashAndAuditsIndependently() {
        given(users.findByEmail("ghost@example.com")).willReturn(Optional.empty());
        given(encoder.encode(anyString())).willReturn("dummy");

        assertThatThrownBy(() -> service.login(new LoginRequest("ghost@example.com", "pw")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
        verify(encoder).matches("pw", "dummy");
        verify(audit).recordIndependent(eq(AuditAction.AUTH_LOGIN_FAILED), isNull(), eq("ghost@example.com"), anyString(), isNull(), any());
    }

    @Test
    void login_wrongPassword_incrementsFailuresAndThrowsInvalid() {
        User u = TestData.inventor("Ada");
        given(users.findByEmail(u.getEmail())).willReturn(Optional.of(u));
        given(encoder.matches("bad", u.getPasswordHash())).willReturn(false);
        given(users.lockIfThresholdReached(eq(u.getId()), eq(5), any(), eq(TestData.NOW))).willReturn(0);

        assertThatThrownBy(() -> service.login(new LoginRequest(u.getEmail(), "bad")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(users).incrementFailedLogins(u.getId(), TestData.NOW);
        verify(users, never()).recordSuccessfulLogin(any(), any());
        verify(audit).recordIndependent(eq(AuditAction.AUTH_LOGIN_FAILED), eq(u.getId()), anyString(), anyString(), any(), any());
    }

    @Test
    void login_fifthFailure_locksAccountFor15MinutesAndAuditsLock() {
        User u = TestData.inventor("Ada");
        given(users.findByEmail(u.getEmail())).willReturn(Optional.of(u));
        given(encoder.matches("bad", u.getPasswordHash())).willReturn(false);
        given(users.lockIfThresholdReached(u.getId(), 5, TestData.NOW.plus(Duration.ofMinutes(15)), TestData.NOW)).willReturn(1);

        assertThatThrownBy(() -> service.login(new LoginRequest(u.getEmail(), "bad")))
                .isInstanceOf(AccountLockedException.class)
                .satisfies(e -> assertThat(((AccountLockedException) e).getRetryAfter()).isEqualTo(Duration.ofMinutes(15)));
        verify(audit).recordIndependent(eq(AuditAction.AUTH_LOCKED), eq(u.getId()), anyString(), anyString(), any(), any());
    }

    @Test
    void login_lockedAccount_rejectsWithoutCheckingPassword() {
        User u = TestData.inventor("Ada");
        u.setLockedUntil(TestData.NOW.plusSeconds(120));
        given(users.findByEmail(u.getEmail())).willReturn(Optional.of(u));

        assertThatThrownBy(() -> service.login(new LoginRequest(u.getEmail(), "right")))
                .isInstanceOf(AccountLockedException.class)
                .satisfies(e -> assertThat(((AccountLockedException) e).getRetryAfter()).isEqualTo(Duration.ofSeconds(120)));
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    void login_disabledUser_treatedAsBadCredentials() {
        User u = TestData.inventor("Ada");
        u.setEnabled(false);
        given(users.findByEmail(u.getEmail())).willReturn(Optional.of(u));

        assertThatThrownBy(() -> service.login(new LoginRequest(u.getEmail(), "right")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(users).incrementFailedLogins(u.getId(), TestData.NOW);
    }

    private RefreshToken refreshToken(User user) {
        RefreshToken t = new RefreshToken();
        t.setId(UUID.randomUUID());
        t.setUser(user);
        t.setFamilyId(UUID.randomUUID());
        t.setTokenHash(RefreshTokenService.hash("raw"));
        t.setCreatedAt(TestData.NOW.minus(Duration.ofHours(1)));
        t.setExpiresAt(TestData.NOW.plus(Duration.ofDays(6)));
        return t;
    }

    @Test
    void refresh_activeToken_rotatesWithinSameFamily() {
        User u = TestData.inventor("Ada");
        RefreshToken current = refreshToken(u);
        given(refreshTokens.lockByTokenHash(RefreshTokenService.hash("raw"))).willReturn(Optional.of(current));
        given(users.findWithRolesById(u.getId())).willReturn(Optional.of(u));
        stubTokenIssuing(u);

        AuthResponse response = service.refresh("raw");

        assertThat(response.refreshToken()).isEqualTo("refresh-raw");
        verify(refreshTokenService).issue(u, current.getFamilyId());
        assertThat(current.getRevokedAt()).isEqualTo(TestData.NOW);
        assertThat(current.getReplacedBy()).isNotNull();
        assertThat(current.getLastUsedAt()).isEqualTo(TestData.NOW);
    }

    @Test
    void refresh_reusedRotatedToken_revokesWholeFamilyAuditsAndRejects() {
        User u = TestData.inventor("Ada");
        RefreshToken rotated = refreshToken(u);
        rotated.setRevokedAt(TestData.NOW.minusSeconds(60));
        rotated.setReplacedBy(UUID.randomUUID());
        given(refreshTokens.lockByTokenHash(anyString())).willReturn(Optional.of(rotated));
        given(refreshTokens.revokeFamily(rotated.getFamilyId(), TestData.NOW)).willReturn(2);

        assertThatThrownBy(() -> service.refresh("raw")).isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokens).revokeFamily(rotated.getFamilyId(), TestData.NOW);
        verify(audit).recordIndependent(eq(AuditAction.AUTH_REFRESH_REUSE), eq(u.getId()), isNull(), eq("SESSION"),
                eq(rotated.getFamilyId()), any());
        verify(refreshTokenService, never()).issue(any(), any());
    }

    @Test
    void refresh_loggedOutToken_rejectsWithoutReuseAlarm() {
        User u = TestData.inventor("Ada");
        RefreshToken revoked = refreshToken(u);
        revoked.setRevokedAt(TestData.NOW.minusSeconds(60));
        given(refreshTokens.lockByTokenHash(anyString())).willReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.refresh("raw")).isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokens, never()).revokeFamily(any(), any());
    }

    @Test
    void refresh_expiredToken_rejects() {
        User u = TestData.inventor("Ada");
        RefreshToken expired = refreshToken(u);
        expired.setExpiresAt(TestData.NOW.minusSeconds(1));
        given(refreshTokens.lockByTokenHash(anyString())).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.refresh("raw")).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_unknownToken_rejects() {
        given(refreshTokens.lockByTokenHash(anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("nope")).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_disabledUser_revokesFamilyAndRejects() {
        User u = TestData.inventor("Ada");
        u.setEnabled(false);
        RefreshToken current = refreshToken(u);
        given(refreshTokens.lockByTokenHash(anyString())).willReturn(Optional.of(current));
        given(users.findWithRolesById(u.getId())).willReturn(Optional.of(u));

        assertThatThrownBy(() -> service.refresh("raw")).isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokens).revokeFamily(current.getFamilyId(), TestData.NOW);
    }

    @Test
    void logout_activeToken_revokesAndAudits() {
        User u = TestData.inventor("Ada");
        RefreshToken current = refreshToken(u);
        given(refreshTokens.findByTokenHash(RefreshTokenService.hash("raw"))).willReturn(Optional.of(current));

        service.logout("raw");

        assertThat(current.getRevokedAt()).isEqualTo(TestData.NOW);
        verify(audit).record(eq(AuditAction.AUTH_LOGOUT), eq(u.getId()), eq(u.getEmail()), eq("SESSION"), eq(current.getFamilyId()), isNull());
    }

    @Test
    void logout_unknownToken_isSilentlyIgnored() {
        given(refreshTokens.findByTokenHash(anyString())).willReturn(Optional.empty());

        service.logout("whatever");

        verify(audit, never()).record(any(), any(UUID.class), any(), any(), any(), any());
    }

    @Test
    void me_knownUser_mapsResponse() {
        User u = TestData.examiner("Grace");
        given(users.findWithRolesById(u.getId())).willReturn(Optional.of(u));

        assertThat(service.me(u.getId()).artUnit()).isEqualTo("2100");
    }

    @Test
    void me_unknownUser_throwsNotFound() {
        UUID id = UUID.randomUUID();
        given(users.findWithRolesById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.me(id)).isInstanceOf(NotFoundException.class);
    }
}
