package gov.openfiling.api.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EntityStatus {
    LARGE("Large entity"),
    SMALL("Small entity"),
    MICRO("Micro entity");

    private final String label;
}
