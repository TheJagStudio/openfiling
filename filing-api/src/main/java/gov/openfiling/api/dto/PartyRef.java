package gov.openfiling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record PartyRef(UUID id, @Schema(example = "Ada Inventor") String displayName) {
}
