package gov.openfiling.api.repository.spec;

import static gov.openfiling.api.repository.spec.SpecSupport.ESCAPE;
import static gov.openfiling.api.repository.spec.SpecSupport.hasText;

import gov.openfiling.api.model.ApplicationStatus;
import gov.openfiling.api.model.ApplicationType;
import gov.openfiling.api.model.PatentApplication;
import gov.openfiling.api.model.User;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

/** Criteria for the owner list, the examiner queue, dashboards and the public search. */
public final class ApplicationSpecifications {

    private ApplicationSpecifications() {
    }

    /** Filter values; {@code null} / empty means "no restriction". */
    public record Filter(
            UUID ownerId,
            boolean filedOnly,
            boolean publishedOnly,
            String q,
            boolean qMatchesOwner,
            boolean qMatchesAbstract,
            Collection<ApplicationStatus> statuses,
            ApplicationType type,
            UUID examinerId,
            boolean unassignedOnly,
            String artUnit,
            LocalDate filedFrom,
            LocalDate filedTo) {

        public static Filter owner(UUID ownerId, String q, Collection<ApplicationStatus> statuses, ApplicationType type) {
            return new Filter(ownerId, false, false, q, false, false, statuses, type, null, false, null, null, null);
        }

        public static Filter published(String q, ApplicationType type) {
            return new Filter(null, true, true, q, false, true, null, type, null, false, null, null, null);
        }
    }

    public static Specification<PatentApplication> matching(Filter f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (f.ownerId() != null) {
                predicates.add(cb.equal(root.get("owner").get("id"), f.ownerId()));
            }
            if (f.filedOnly()) {
                predicates.add(cb.notEqual(root.get("status"), ApplicationStatus.DRAFT));
            }
            if (f.publishedOnly()) {
                predicates.add(cb.isTrue(root.get("published")));
            }
            if (hasText(f.q())) {
                predicates.add(textMatches(root, cb, f));
            }
            if (f.statuses() != null && !f.statuses().isEmpty()) {
                predicates.add(root.get("status").in(f.statuses()));
            }
            if (f.type() != null) {
                predicates.add(cb.equal(root.get("applicationType"), f.type()));
            }
            if (f.examinerId() != null) {
                predicates.add(cb.equal(root.get("examiner").get("id"), f.examinerId()));
            }
            if (f.unassignedOnly()) {
                predicates.add(cb.isNull(root.get("examiner")));
            }
            if (hasText(f.artUnit())) {
                predicates.add(cb.equal(root.get("artUnit"), f.artUnit().trim()));
            }
            if (f.filedFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("filingDate"), f.filedFrom()));
            }
            if (f.filedTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("filingDate"), f.filedTo()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate textMatches(Root<PatentApplication> root, CriteriaBuilder cb, Filter f) {
        String pattern = SpecSupport.containsPattern(f.q());
        List<Predicate> any = new ArrayList<>();
        any.add(cb.like(cb.lower(root.get("title")), pattern, ESCAPE));
        String digits = SpecSupport.digitsOnly(f.q());
        if (digits.length() >= 2) {
            any.add(cb.like(root.get("applicationNumber"), "%" + SpecSupport.escape(digits) + "%", ESCAPE));
        }
        if (f.qMatchesOwner()) {
            Join<PatentApplication, User> owner = root.join("owner", JoinType.INNER);
            any.add(cb.like(cb.lower(owner.get("displayName")), pattern, ESCAPE));
        }
        if (f.qMatchesAbstract()) {
            any.add(cb.like(cb.lower(root.get("abstractText")), pattern, ESCAPE));
        }
        return cb.or(any.toArray(Predicate[]::new));
    }
}
