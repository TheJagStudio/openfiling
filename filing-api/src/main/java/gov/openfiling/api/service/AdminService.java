package gov.openfiling.api.service;

import gov.openfiling.api.dto.AdminCreateUserRequest;
import gov.openfiling.api.dto.AdminUpdateUserRequest;
import gov.openfiling.api.dto.AdminUserResponse;
import gov.openfiling.api.dto.AuditEventResponse;
import gov.openfiling.api.dto.CategoryCount;
import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.dto.PageResponse;
import gov.openfiling.api.dto.RedactionCounts;
import gov.openfiling.api.dto.SystemStatusResponse;
import gov.openfiling.api.exception.BusinessRuleException;
import gov.openfiling.api.exception.ConflictException;
import gov.openfiling.api.exception.FieldProblem;
import gov.openfiling.api.exception.NotFoundException;
import gov.openfiling.api.mapper.AccountMapper;
import gov.openfiling.api.mapper.RedactionJson;
import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.AuditEvent;
import gov.openfiling.api.model.DocumentStatus;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.ApplicationRepository;
import gov.openfiling.api.repository.AuditEventRepository;
import gov.openfiling.api.repository.DocumentRepository;
import gov.openfiling.api.repository.IdCountRow;
import gov.openfiling.api.repository.RedactionRepository;
import gov.openfiling.api.repository.RefreshTokenRepository;
import gov.openfiling.api.repository.UserRepository;
import gov.openfiling.api.repository.spec.AuditSpecifications;
import gov.openfiling.api.repository.spec.SortSupport;
import gov.openfiling.api.repository.spec.UserSpecifications;
import gov.openfiling.api.security.Viewer;
import gov.openfiling.api.storage.DocumentStorage;
import gov.openfiling.api.storage.RedactionInvoker;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Administration: users and roles, audit log, system status. */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminService {

    static final Map<String, String> USER_SORTS = Map.of(
            "createdAt", "createdAt", "email", "email", "displayName", "displayName", "lastLoginAt", "lastLoginAt");

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final ApplicationRepository applications;
    private final DocumentRepository documents;
    private final RedactionRepository redactions;
    private final AuditEventRepository auditEvents;
    private final AuthService auth;
    private final AccountMapper accounts;
    private final AuditService audit;
    private final DocumentStorage storage;
    private final RedactionInvoker redactionInvoker;
    private final RedactionService redactionService;
    private final gov.openfiling.api.config.AppProperties.Storage storageProps;
    private final Clock clock;

    public PageResponse<AdminUserResponse> users(String q, Role role, PageParams paging) {
        Pageable pageable = SortSupport.pageable(paging, USER_SORTS, "createdAt", Sort.Direction.DESC, "id");
        Page<User> page = users.findAll(UserSpecifications.matching(q, role), pageable);
        Map<UUID, Long> counts = page.isEmpty() ? Map.of()
                : applications.countByOwners(page.getContent().stream().map(User::getId).toList()).stream()
                        .collect(Collectors.toMap(IdCountRow::getId, IdCountRow::getTotal));
        Instant now = clock.instant();
        return PageResponse.of(page, u -> toAdminResponse(u, counts.getOrDefault(u.getId(), 0L), now));
    }

    @Transactional
    public AdminUserResponse createUser(Viewer admin, AdminCreateUserRequest request) {
        String email = AuthService.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists");
        }
        User user = auth.createUser(email, request.password(), request.displayName().trim(), EnumSet.copyOf(request.roles()),
                request.artUnit());
        audit.record(AuditAction.ADMIN_USER_CREATED, admin, AuthService.USER, user.getId(),
                AuditService.details("email", user.getEmail(), "roles", accounts.roleNames(user.getRoles()), "artUnit", user.getArtUnit()));
        return toAdminResponse(user, 0, clock.instant());
    }

    /** Partial update. Admins cannot remove their own ADMIN role or disable themselves (422). */
    @Transactional
    public AdminUserResponse updateUser(Viewer admin, UUID id, AdminUpdateUserRequest request) {
        User user = users.findWithRolesById(id).orElseThrow(() -> NotFoundException.of("User", id));
        boolean self = admin.id().equals(id);
        List<FieldProblem> problems = new ArrayList<>();
        if (self && request.roles() != null && !request.roles().contains(Role.ADMIN)) {
            problems.add(new FieldProblem("roles", "You cannot remove your own ADMIN role"));
        }
        if (self && Boolean.FALSE.equals(request.enabled())) {
            problems.add(new FieldProblem("enabled", "You cannot disable your own account"));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleException("The change is not allowed", problems);
        }
        Map<String, Object> changes = new java.util.LinkedHashMap<>();
        Instant now = clock.instant();
        if (request.roles() != null && !request.roles().equals(user.getRoles())) {
            changes.put("roles", Map.of("from", accounts.roleNames(user.getRoles()), "to", accounts.roleNames(request.roles())));
            user.setRoles(EnumSet.copyOf(request.roles()));
        }
        if (request.enabled() != null && request.enabled() != user.isEnabled()) {
            changes.put("enabled", request.enabled());
            user.setEnabled(request.enabled());
        }
        if (request.artUnit() != null) {
            String artUnit = request.artUnit().isBlank() ? null : request.artUnit().trim();
            if (!Objects.equals(artUnit, user.getArtUnit())) {
                changes.put("artUnit", artUnit == null ? "" : artUnit);
                user.setArtUnit(artUnit);
            }
        }
        if (Boolean.TRUE.equals(request.unlock()) && (user.getLockedUntil() != null || user.getFailedLogins() > 0)) {
            changes.put("unlocked", true);
            user.setLockedUntil(null);
            user.setFailedLogins(0);
        }
        users.saveAndFlush(user);
        if (Boolean.FALSE.equals(request.enabled())) {
            refreshTokens.revokeAllOfUser(user.getId(), now);
        }
        if (!changes.isEmpty()) {
            audit.record(AuditAction.ADMIN_USER_UPDATED, admin, AuthService.USER, user.getId(), changes);
        }
        long apps = applications.countByOwners(List.of(user.getId())).stream().mapToLong(IdCountRow::getTotal).sum();
        return toAdminResponse(users.findWithRolesById(id).orElse(user), apps, now);
    }

    public PageResponse<AuditEventResponse> audit(String actor, AuditAction action, String resourceType, String resourceId,
            Instant from, Instant to, PageParams paging) {
        Pageable pageable = PageRequest.of(paging.pageOrDefault(), paging.sizeOrDefault(),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        Page<AuditEvent> page = auditEvents.findAll(
                AuditSpecifications.matching(actor, action, resourceType, resourceId, from, to), pageable);
        return PageResponse.of(page, e -> new AuditEventResponse(e.getId(), e.getOccurredAt(), e.getActorId(), e.getActorEmail(),
                e.getAction(), e.getResourceType(), e.getResourceId(), e.getIp(), e.getUserAgent(), e.getRequestId(),
                RedactionJson.readTree(e.getDetails())));
    }

    /** Component health (each check isolated and short) plus counts. Runs without a surrounding transaction. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SystemStatusResponse system() {
        List<SystemStatusResponse.Component> components = new ArrayList<>();
        boolean dbUp;
        try {
            users.count();
            dbUp = true;
            components.add(SystemStatusResponse.Component.up("database", "PostgreSQL reachable"));
        } catch (RuntimeException e) {
            dbUp = false;
            components.add(SystemStatusResponse.Component.down("database", "Unreachable: " + e.getClass().getSimpleName()));
        }
        DocumentStorage.BucketHealth docs = storage.bucketHealth(storageProps.documentsBucket());
        DocumentStorage.BucketHealth copies = storage.bucketHealth(storageProps.publicBucket());
        components.add(new SystemStatusResponse.Component("storage", docs.up() && copies.up() ? "UP" : "DOWN",
                docs.detail() + "; " + copies.detail()));
        RedactionInvoker.Health lambda = redactionInvoker.health();
        components.add(new SystemStatusResponse.Component("redaction-lambda", lambda.up() ? "UP" : "DOWN", lambda.detail()));

        if (!dbUp) {
            return new SystemStatusResponse(components, new SystemStatusResponse.Counts(0, 0, 0, 0, 0),
                    new SystemStatusResponse.Redactions(0, 0, 0, 0, 0, List.of()));
        }
        SystemStatusResponse.Counts counts = new SystemStatusResponse.Counts(
                users.count(),
                applications.count(),
                applications.countByStatusNot(ApplicationStatus.DRAFT),
                documents.countByStatusNot(DocumentStatus.EXPIRED),
                documents.sumUploadedBytes());
        RedactionCounts rc = redactionService.counts();
        List<CategoryCount> byCategory = redactions.totalsByCategory().stream()
                .map(r -> new CategoryCount(r.getCategory(), r.getTotal()))
                .toList();
        return new SystemStatusResponse(components, counts, new SystemStatusResponse.Redactions(
                rc.pending(), rc.processing(), rc.completed(), rc.failed(), redactions.sumCompletedRedactions(), byCategory));
    }

    private AdminUserResponse toAdminResponse(User u, long applicationCount, Instant now) {
        return new AdminUserResponse(u.getId(), u.getEmail(), u.getDisplayName(), accounts.roleNames(u.getRoles()), u.getArtUnit(),
                u.isEnabled(), u.isLockedAt(now), u.getLastLoginAt(), u.getCreatedAt(), applicationCount);
    }
}
