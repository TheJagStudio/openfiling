package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Contract page shape (deliberately not Spring's {@code Page} serialization). */
@Schema(description = "A page of results")
public record PageResponse<T>(
        List<T> content,
        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "123") long totalElements,
        @Schema(example = "7") int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, ? extends T> mapper) {
        List<T> content = page.getContent().stream().<T>map(mapper).toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public static <T> PageResponse<T> of(Page<?> page, List<T> content) {
        return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
