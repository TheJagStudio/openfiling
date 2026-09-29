package gov.openfiling.api.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import gov.openfiling.api.model.Role;

import java.util.Arrays;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Authenticated-request helpers for the MVC slices. */
final class Jwts {

    private Jwts() {
    }

    static RequestPostProcessor as(UUID userId, Role... roles) {
        return jwt()
                .jwt(j -> j.subject(userId.toString())
                        .claim("email", "user@example.test")
                        .claim("roles", Arrays.stream(roles).map(Role::name).toList()))
                .authorities(Arrays.stream(roles)
                        .<org.springframework.security.core.GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                        .toList());
    }

    static RequestPostProcessor inventor() {
        return as(UUID.randomUUID(), Role.INVENTOR);
    }

    static RequestPostProcessor examiner() {
        return as(UUID.randomUUID(), Role.EXAMINER);
    }

    static RequestPostProcessor admin() {
        return as(UUID.randomUUID(), Role.ADMIN, Role.EXAMINER);
    }
}
