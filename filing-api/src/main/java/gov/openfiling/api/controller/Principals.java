package gov.openfiling.api.controller;

import gov.openfiling.api.config.JwtConfig;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.security.Viewer;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

final class Principals {

    private Principals() {
    }

    /** The user id is the JWT {@code sub}. */
    static UUID userId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new BadCredentialsException("Missing subject");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new BadCredentialsException("Malformed subject");
        }
    }

    /** Id, e-mail and roles from the access token (unknown role names are ignored). */
    static Viewer viewer(Jwt jwt) {
        UUID id = userId(jwt);
        Set<Role> roles = EnumSet.noneOf(Role.class);
        List<String> names = jwt.getClaimAsStringList(JwtConfig.ROLES_CLAIM);
        if (names != null) {
            for (String name : names) {
                try {
                    roles.add(Role.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                    // forward compatibility: a newer token may carry roles this build does not know
                }
            }
        }
        return new Viewer(id, jwt.getClaimAsString(JwtConfig.EMAIL_CLAIM), roles);
    }
}
