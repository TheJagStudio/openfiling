package gov.openfiling.api.repository.spec;

import static gov.openfiling.api.repository.spec.SpecSupport.ESCAPE;
import static gov.openfiling.api.repository.spec.SpecSupport.hasText;

import gov.openfiling.api.model.Role;
import gov.openfiling.api.model.User;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.criteria.Root;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

public final class UserSpecifications {

    private UserSpecifications() {
    }

    /** Email or display name contains {@code q}; has {@code role} (EXISTS subquery, so paging stays exact). */
    public static Specification<User> matching(String q, Role role) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (hasText(q)) {
                String pattern = SpecSupport.containsPattern(q);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), pattern, ESCAPE),
                        cb.like(cb.lower(root.get("displayName")), pattern, ESCAPE)));
            }
            if (role != null) {
                Subquery<UUID> sub = query.subquery(UUID.class);
                Root<User> u = sub.from(User.class);
                sub.select(u.get("id")).where(cb.equal(u.get("id"), root.get("id")), cb.isMember(role, u.get("roles")));
                predicates.add(cb.exists(sub));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
