package gov.openfiling.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import gov.openfiling.api.config.AppProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityComponentsTest {

    private static AppProperties.Security security(int perMinute) {
        return new AppProperties.Security("token", 5, Duration.ofMinutes(15), perMinute);
    }

    private static MockHttpServletResponse call(AuthRateLimitFilter filter, String method, String uri, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void authRateLimit_exceedingPerIpBudget_returns429WithRetryAfter() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(security(3), new ProblemResponseWriter());

        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = call(filter, "POST", "/api/v1/auth/login", "10.0.0.1");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(limited.getHeader("Retry-After"))).isPositive();
        assertThat(limited.getContentType()).startsWith("application/problem+json");
        assertThat(limited.getContentAsString()).contains("\"status\":429");
        // Other clients and non-auth routes are unaffected.
        assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.2").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/v1/auth/me", "10.0.0.1").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/applications", "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void internalTokenFilter_wrongOrMissingToken_returns401() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter("secret", new ProblemResponseWriter());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/storage/events");
        request.addHeader(InternalTokenFilter.HEADER, "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void jwtKeyProvider_persistsAndReloadsSameKey(@TempDir Path dir) {
        AppProperties.Jwt props = new AppProperties.Jwt("openfiling", Duration.ofMinutes(15), Duration.ofDays(7), dir.toString());

        RSAKey first = new JwtKeyProvider(props).signingKey();
        RSAKey second = new JwtKeyProvider(props).signingKey();

        assertThat(Files.exists(dir.resolve("openfiling-jwt-private.pem"))).isTrue();
        assertThat(Files.exists(dir.resolve("openfiling-jwt-public.pem"))).isTrue();
        assertThat(second.getKeyID()).isEqualTo(first.getKeyID());
    }

    @Test
    void viewer_rolesAndCapacities() {
        Viewer examiner = Viewer.of(UUID.randomUUID(), "e@x", gov.openfiling.api.model.Role.EXAMINER);
        Viewer admin = Viewer.of(UUID.randomUUID(), "a@x", gov.openfiling.api.model.Role.ADMIN);
        Viewer nobody = new Viewer(UUID.randomUUID(), null, null);

        assertThat(examiner.isExaminer()).isTrue();
        assertThat(examiner.isAdmin()).isFalse();
        assertThat(admin.isExaminer()).isTrue();
        assertThat(nobody.isExaminer()).isFalse();
        assertThat(Viewer.of(UUID.randomUUID(), "x").roles()).isEmpty();
    }

    @Test
    void requestMetadata_outsideRequest_isEmpty() {
        assertThat(RequestMetadata.current().ip()).isNull();
        assertThat(RequestMetadata.truncate("abcdef", 3)).isEqualTo("abc");
        assertThat(RequestMetadata.truncate(null, 3)).isNull();
    }
}
