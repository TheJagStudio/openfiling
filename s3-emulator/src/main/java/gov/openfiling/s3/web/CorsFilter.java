package gov.openfiling.s3.web;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import gov.openfiling.s3.S3Exception;
import gov.openfiling.s3.config.EmulatorProperties;

/**
 * Bucket CORS configuration equivalent. Preflight ({@code OPTIONS}) requests are answered here without a signature;
 * for every other request (including error responses) the {@code Access-Control-*} headers are added up front.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorsFilter extends OncePerRequestFilter {

    private final List<String> allowedOrigins;
    private final List<String> allowedMethods;
    private final String exposedHeaders;
    private final String maxAge;

    public CorsFilter(EmulatorProperties properties) {
        EmulatorProperties.Cors cors = properties.cors();
        this.allowedOrigins = cors.allowedOrigins().stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.allowedMethods = cors.allowedMethods().stream().map(m -> m.trim().toUpperCase(Locale.ROOT)).toList();
        this.exposedHeaders = String.join(", ", cors.exposedHeaders());
        this.maxAge = Long.toString(cors.maxAgeSeconds());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        boolean originAllowed = origin != null && (allowedOrigins.contains("*") || allowedOrigins.contains(origin));

        if ("OPTIONS".equals(request.getMethod())) {
            String requestedMethod = request.getHeader("Access-Control-Request-Method");
            if (origin == null || requestedMethod == null) {
                S3Xml.writeError(request, response, new S3Exception(400, "BadRequest",
                        "Insufficient information. Origin request header needed."));
                return;
            }
            if (!originAllowed || !allowedMethods.contains(requestedMethod.trim().toUpperCase(Locale.ROOT))) {
                S3Xml.writeError(request, response, new S3Exception(403, "AccessForbidden",
                        "CORSResponse: This CORS request is not allowed. This is usually because the evalution of "
                                + "Origin, request method / Access-Control-Request-Method or Access-Control-Request-Headers "
                                + "are not whitelisted by the resource's CORS spec."));
                return;
            }
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.addHeader("Vary", "Origin, Access-Control-Request-Headers, Access-Control-Request-Method");
            response.setHeader("Access-Control-Allow-Methods", String.join(", ", allowedMethods));
            String requestedHeaders = request.getHeader("Access-Control-Request-Headers");
            if (requestedHeaders != null && !requestedHeaders.isBlank()) {
                response.setHeader("Access-Control-Allow-Headers", requestedHeaders);
            }
            response.setHeader("Access-Control-Expose-Headers", exposedHeaders);
            response.setHeader("Access-Control-Max-Age", maxAge);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLength(0);
            return;
        }

        if (originAllowed) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.addHeader("Vary", "Origin");
            response.setHeader("Access-Control-Expose-Headers", exposedHeaders);
        }
        chain.doFilter(request, response);
    }
}
