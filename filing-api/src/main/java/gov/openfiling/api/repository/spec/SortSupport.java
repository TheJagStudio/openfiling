package gov.openfiling.api.repository.spec;

import gov.openfiling.api.dto.PageParams;
import gov.openfiling.api.exception.BadRequestException;

import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Validates {@code sort=field,asc|desc} against an allow-list of API field &rarr; entity path mappings. */
public final class SortSupport {

    private SortSupport() {
    }

    /**
     * @param allowed      API sort field &rarr; entity attribute path
     * @param defaultField API field used when {@code sort} is absent
     * @param defaultDir   direction used when absent (or when only a field is given)
     * @param tieBreaker   stable secondary ordering (entity path), e.g. {@code id}
     */
    public static Pageable pageable(PageParams paging, Map<String, String> allowed, String defaultField,
            Sort.Direction defaultDir, String tieBreaker) {
        String raw = paging.sort();
        String field = defaultField;
        Sort.Direction direction = defaultDir;
        if (raw != null && !raw.isBlank()) {
            String[] parts = raw.split(",", 2);
            field = parts[0].trim();
            if (parts.length > 1) {
                direction = parts[1].trim().toLowerCase(Locale.ROOT).equals("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
            }
        }
        String path = allowed.get(field);
        if (path == null) {
            throw new BadRequestException("sort field must be one of " + String.join(", ", new TreeSet<>(allowed.keySet())));
        }
        Sort sort = Sort.by(new Sort.Order(direction, path).nullsLast());
        if (tieBreaker != null && !tieBreaker.equals(path)) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, tieBreaker));
        }
        return PageRequest.of(paging.pageOrDefault(), paging.sizeOrDefault(), sort);
    }
}
