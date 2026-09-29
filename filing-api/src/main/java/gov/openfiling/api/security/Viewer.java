package gov.openfiling.api.security;

import gov.openfiling.api.model.Role;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller as seen by services: user id, e-mail and roles from the access token.
 * Services make every visibility / ownership decision from this value, never from URLs.
 */
public record Viewer(UUID id, String email, Set<Role> roles) {

    public Viewer {
        roles = roles == null || roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
    }

    public static Viewer of(UUID id, String email, Role... roles) {
        return new Viewer(id, email, roles.length == 0 ? EnumSet.noneOf(Role.class) : EnumSet.of(roles[0], roles));
    }

    /** EXAMINER or ADMIN (admins may perform every examiner action). */
    public boolean isExaminer() {
        return roles.contains(Role.EXAMINER) || roles.contains(Role.ADMIN);
    }

    public boolean isAdmin() {
        return roles.contains(Role.ADMIN);
    }
}
