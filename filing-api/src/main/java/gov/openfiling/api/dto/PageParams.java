package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** Paging/sorting query parameters: {@code page} (0-based), {@code size} (1-100), {@code sort=field,asc|desc}. */
public record PageParams(
        @Parameter(description = "0-based page index", example = "0") @Min(0) @Max(100_000) Integer page,
        @Parameter(description = "Page size (max 100)", example = "20") @Min(1) @Max(100) Integer size,
        @Parameter(description = "field,asc|desc", example = "updatedAt,desc")
        @Pattern(regexp = "^[A-Za-z]{1,40}(,(?i:asc|desc))?$", message = "must look like field,asc or field,desc")
        String sort) {

    public static final int DEFAULT_SIZE = 20;

    public static PageParams defaults() {
        return new PageParams(0, DEFAULT_SIZE, null);
    }

    public static PageParams of(int page, int size, String sort) {
        return new PageParams(page, size, sort);
    }

    public int pageOrDefault() {
        return page == null ? 0 : page;
    }

    public int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }
}
