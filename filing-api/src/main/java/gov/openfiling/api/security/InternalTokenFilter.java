package gov.openfiling.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /internal/**}: requires {@code X-Internal-Token} equal (constant-time) to the
 * shared secret, then authenticates the caller as {@code ROLE_INTERNAL}. Not a Spring bean, so it
 * is only ever installed in the internal security filter chain.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    public static final String ROLE = "INTERNAL";

    private final byte[] expected;
    private final ProblemResponseWriter problems;

    public InternalTokenFilter(String expectedToken, ProblemResponseWriter problems) {
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new IllegalStateException("app.security.internal-token (INTERNAL_TOKEN) must be set");
        }
        this.expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            problems.write(request, response, HttpStatus.UNAUTHORIZED, "A valid X-Internal-Token header is required");
            return;
        }
        SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "internal", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
        SecurityContextHolder.getContextHolderStrategy().setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.getContextHolderStrategy().clearContext();
        }
    }
}
