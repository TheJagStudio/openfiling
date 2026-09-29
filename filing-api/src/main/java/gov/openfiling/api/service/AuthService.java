package gov.openfiling.api.service;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.dto.AuthResponse;
import gov.openfiling.api.dto.LoginRequest;
import gov.openfiling.api.dto.RegisterRequest;
import gov.openfiling.api.dto.UserResponse;
import gov.openfiling.api.exception.AccountLockedException;
import gov.openfiling.api.exception.ConflictException;
import gov.openfiling.api.exception.InvalidCredentialsException;
import gov.openfiling.api.exception.InvalidRefreshTokenException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.mapper.AccountMapper;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.RefreshToken;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.RefreshTokenRepository;
import gov.openfiling.api.repository.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login (brute-force lockout), refresh-token rotation with reuse detection, logout
 * and the current user.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthService {

    static final String USER = "USER";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final AccountMapper mapper;
    private final AuditService audit;
    private final AppProperties.Security security;
    private final Clock clock;

    /** Hash compared against when the email is unknown, so response time does not reveal accounts. */
    private volatile String timingDummyHash;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists");
        }
        User user = createUser(email, request.password(), request.displayName().trim(), EnumSet.of(Role.INVENTOR), null);
        audit.record(AuditAction.USER_REGISTERED, user.getId(), user.getEmail(), USER, user.getId(), null);
        log.info("Registered user {}", user.getId());
        return authResponse(user, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
    public AuthResponse login(LoginRequest request) {
        Instant now = clock.instant();
        String email = normalizeEmail(request.email());
        Optional<User> found = users.findByEmail(email);
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash());
            audit.recordIndependent(AuditAction.AUTH_LOGIN_FAILED, null, email, USER, null,
                    AuditService.details("reason", "unknown_email"));
            throw new InvalidCredentialsException();
        }
        User user = found.get();
        if (user.isLockedAt(now)) {
            audit.recordIndependent(AuditAction.AUTH_LOGIN_FAILED, user.getId(), email, USER, user.getId(),
                    AuditService.details("reason", "locked"));
            throw new AccountLockedException(Duration.between(now, user.getLockedUntil()));
        }
        if (!user.isEnabled() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            users.incrementFailedLogins(user.getId(), now);
            Instant until = now.plus(security.lockoutDuration());
            if (users.lockIfThresholdReached(user.getId(), security.maxFailedLogins(), until, now) > 0) {
                log.warn("User {} locked until {} after {} failed logins", user.getId(), until, security.maxFailedLogins());
                audit.recordIndependent(AuditAction.AUTH_LOCKED, user.getId(), email, USER, user.getId(),
                        AuditService.details("lockedUntil", until.toString(), "failedLogins", security.maxFailedLogins()));
                throw new AccountLockedException(security.lockoutDuration());
            }
            audit.recordIndependent(AuditAction.AUTH_LOGIN_FAILED, user.getId(), email, USER, user.getId(),
                    AuditService.details("reason", user.isEnabled() ? "bad_password" : "disabled"));
            throw new InvalidCredentialsException();
        }
        users.recordSuccessfulLogin(user.getId(), now);
        user.setLastLoginAt(now); // the bulk update detached the entity; keep the response current
        audit.record(AuditAction.AUTH_LOGIN_SUCCESS, user.getId(), email, USER, user.getId(), null);
        return authResponse(user, UUID.randomUUID());
    }

    /**
     * Rotates a refresh token. Presenting an already rotated token is treated as theft: the whole
     * family (session) is revoked and the event audited.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthResponse refresh(String rawToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.lockByTokenHash(RefreshTokenService.hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        UUID userId = token.getUser().getId();
        if (token.getRevokedAt() != null) {
            if (token.getReplacedBy() != null) {
                int revoked = refreshTokens.revokeFamily(token.getFamilyId(), now);
                log.warn("Refresh token reuse detected for user {}; revoked {} token(s) of the session", userId, revoked);
                audit.recordIndependent(AuditAction.AUTH_REFRESH_REUSE, userId, null, "SESSION", token.getFamilyId(),
                        AuditService.details("revokedTokens", revoked));
            }
            throw new InvalidRefreshTokenException();
        }
        if (!token.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }
        User user = users.findWithRolesById(userId).orElseThrow(InvalidRefreshTokenException::new);
        if (!user.isEnabled() || user.isLockedAt(now)) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            throw new InvalidRefreshTokenException();
        }
        TokenService.IssuedToken access = tokens.issue(user);
        RefreshTokenService.Issued next = refreshTokenService.issue(user, token.getFamilyId());
        token.setRevokedAt(now);
        token.setReplacedBy(next.token().getId());
        token.setLastUsedAt(now);
        next.token().setLastUsedAt(now);
        return AuthResponse.bearer(access.value(), access.expiresInSeconds(), next.rawToken(),
                refreshTokenService.ttlSeconds(), mapper.toResponse(user));
    }

    /** Revokes the presented token; unknown or already revoked tokens are ignored (no enumeration). */
    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHash(RefreshTokenService.hash(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(clock.instant());
                User user = token.getUser();
                audit.record(AuditAction.AUTH_LOGOUT, user.getId(), user.getEmail(), "SESSION", token.getFamilyId(), null);
            }
        });
    }

    public UserResponse me(UUID userId) {
        return users.findWithRolesById(userId)
                .map(mapper::toResponse)
                .orElseThrow(() -> NotFoundException.of("User", userId));
    }

    /** Creates a user; also used by admin user creation and the dev seed. */
    @Transactional
    public User createUser(String email, String rawPassword, String displayName, Set<Role> roles, String artUnit) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(normalizeEmail(email));
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setDisplayName(displayName);
        user.setArtUnit(artUnit == null || artUnit.isBlank() ? null : artUnit.trim());
        user.setRoles(EnumSet.copyOf(roles));
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("An account with this email already exists");
        }
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private AuthResponse authResponse(User user, UUID familyId) {
        TokenService.IssuedToken access = tokens.issue(user);
        RefreshTokenService.Issued refresh = refreshTokenService.issue(user, familyId);
        return AuthResponse.bearer(access.value(), access.expiresInSeconds(), refresh.rawToken(),
                refreshTokenService.ttlSeconds(), mapper.toResponse(user));
    }

    private String dummyHash() {
        String hash = timingDummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode(UUID.randomUUID().toString());
            timingDummyHash = hash;
        }
        return hash;
    }
}
