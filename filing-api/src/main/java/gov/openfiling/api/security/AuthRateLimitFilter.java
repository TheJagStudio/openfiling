package gov.openfiling.api.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gov.openfiling.api.config.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client-IP token bucket (Bucket4j, greedy refill) for {@code POST /api/v1/auth/**}: slows down
 * credential stuffing and registration spam before any password hashing happens. The client IP is
 * {@link HttpServletRequest#getRemoteAddr()}, which honours {@code X-Forwarded-For} because
 * {@code server.forward-headers-strategy=framework} installs the forwarded-header filter first.
 * Buckets live in a size-bounded Caffeine map that forgets idle clients.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    static final String PATH_PREFIX = "/api/v1/auth/";

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();
    private final int perMinute;
    private final ProblemResponseWriter problems;

    public AuthRateLimitFilter(AppProperties.Security security, ProblemResponseWriter problems) {
        this.perMinute = Math.max(1, security.authRateLimitPerMinute());
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        ConsumptionProbe probe = buckets.get(ip, k -> newBucket()).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
        log.warn("Auth rate limit exceeded for a client on {}", request.getRequestURI());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        problems.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                "Too many authentication requests. Try again in " + retryAfter + " second(s).");
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)).build())
                .build();
    }
}
