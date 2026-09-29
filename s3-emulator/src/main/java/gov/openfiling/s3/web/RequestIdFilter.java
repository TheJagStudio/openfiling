package gov.openfiling.s3.web;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request an S3-style request id: echoed as {@code x-amz-request-id} (and {@code x-amz-id-2}),
 * put in the MDC for log lines and used as {@code <RequestId>} in error documents.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "x-amz-request-id";
    public static final String MDC_KEY = "requestId";
    static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        String requestId = HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
        request.setAttribute(ATTRIBUTE, requestId);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        response.setHeader("x-amz-id-2", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String currentId(HttpServletRequest request) {
        Object id = request.getAttribute(ATTRIBUTE);
        return id == null ? "" : id.toString();
    }
}
