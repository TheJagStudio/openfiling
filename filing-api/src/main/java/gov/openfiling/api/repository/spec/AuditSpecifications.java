package gov.openfiling.api.repository.spec;

import static gov.openfiling.api.repository.spec.SpecSupport.ESCAPE;
import static gov.openfiling.api.repository.spec.SpecSupport.hasText;

import gov.openfiling.api.model.AuditAction;
import gov.openfiling.api.model.AuditEvent;
import jakarta.persistence.criteria.Predicate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

public final class AuditSpecifications {

    private AuditSpecifications() {
    }

    /**
     * @param actor a user id (exact) or an e-mail fragment (contains, case-insensitive)
     */
    public static Specification<AuditEvent> matching(String actor, AuditAction action, String resourceType,
            String resourceId, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (hasText(actor)) {
                UUID actorId = parseUuid(actor.trim());
                predicates.add(actorId != null
                        ? cb.equal(root.get("actorId"), actorId)
                        : cb.like(cb.lower(root.get("actorEmail")), SpecSupport.containsPattern(actor), ESCAPE));
            }
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (hasText(resourceType)) {
                predicates.add(cb.equal(root.get("resourceType"), resourceType.trim()));
            }
            if (hasText(resourceId)) {
                predicates.add(cb.equal(root.get("resourceId"), resourceId.trim()));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
