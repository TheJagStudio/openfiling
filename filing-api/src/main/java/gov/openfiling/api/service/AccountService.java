package gov.openfiling.api.service;

import gov.openfiling.api.dto.ChangePasswordRequest;
import gov.openfiling.api.dto.SessionResponse;
import gov.openfiling.api.dto.UpdateProfileRequest;
import gov.openfiling.api.dto.UserResponse;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.mapper.AccountMapper;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.RefreshToken;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.RefreshTokenRepository;
import gov.openfiling.api.repository.UserRepository;
import gov.openfiling.api.security.Viewer;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile, password and session (refresh-token family) management of the signed-in user. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccountMapper mapper;
    private final AuditService audit;
    private final Clock clock;

    @Transactional
    public UserResponse updateProfile(Viewer viewer, UpdateProfileRequest request) {
        User user = load(viewer);
        user.setDisplayName(request.displayName().trim());
        return mapper.toResponse(users.saveAndFlush(user));
    }

    /** Verifies the current password, stores the new hash and signs out every session. */
    @Transactional
    public void changePassword(Viewer viewer, ChangePasswordRequest request) {
        User user = load(viewer);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw BusinessRuleException.of("currentPassword", "The current password is incorrect");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw BusinessRuleException.of("newPassword", "The new password must differ from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        users.saveAndFlush(user);
        int revoked = refreshTokens.revokeAllOfUser(user.getId(), clock.instant());
        audit.record(AuditAction.PASSWORD_CHANGED, viewer, AuthService.USER, user.getId(),
                AuditService.details("revokedSessions", revoked));
    }

    /** Active sessions: one per refresh-token family with a live (unrevoked, unexpired) head token. */
    public List<SessionResponse> sessions(Viewer viewer) {
        Instant now = clock.instant();
        Map<UUID, RefreshToken> heads = new LinkedHashMap<>();
        for (RefreshToken token : refreshTokens.findActiveByUser(viewer.id(), now)) {
            heads.putIfAbsent(token.getFamilyId(), token);
        }
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<UUID, Instant> started = refreshTokens.familyStarts(heads.keySet()).stream()
                .collect(Collectors.toMap(RefreshTokenRepository.FamilyStartRow::getId,
                        RefreshTokenRepository.FamilyStartRow::getStartedAt));
        return heads.values().stream()
                .map(t -> new SessionResponse(
                        t.getFamilyId(),
                        started.getOrDefault(t.getFamilyId(), t.getCreatedAt()),
                        t.getLastUsedAt() != null ? t.getLastUsedAt() : t.getCreatedAt(),
                        t.getExpiresAt(),
                        t.getUserAgent(),
                        t.getIp()))
                .toList();
    }

    @Transactional
    public void revokeSession(Viewer viewer, UUID sessionId) {
        int revoked = refreshTokens.revokeFamilyOfUser(viewer.id(), sessionId, clock.instant());
        if (revoked == 0 && !refreshTokens.existsByFamilyIdAndUser_Id(sessionId, viewer.id())) {
            throw NotFoundException.of("Session", sessionId);
        }
        if (revoked > 0) {
            audit.record(AuditAction.AUTH_LOGOUT, viewer, "SESSION", sessionId, AuditService.details("scope", "session"));
        }
    }

    @Transactional
    public void revokeAll(Viewer viewer) {
        int revoked = refreshTokens.revokeAllOfUser(viewer.id(), clock.instant());
        audit.record(AuditAction.AUTH_LOGOUT, viewer, AuthService.USER, viewer.id(),
                AuditService.details("scope", "all", "revokedTokens", revoked));
    }

    private User load(Viewer viewer) {
        return users.findWithRolesById(viewer.id()).orElseThrow(() -> NotFoundException.of("User", viewer.id()));
    }
}
