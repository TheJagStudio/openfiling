package gov.openfiling.api.security;

import gov.openfiling.api.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Client metadata of the current HTTP request (ip, user agent, request id) for audit rows and
 * refresh-token sessions. All values are {@code null} outside a request (scheduled jobs, tests).
 * The ip already reflects {@code X-Forwarded-For} ({@code server.forward-headers-strategy=framework}).
 */
public record RequestMetadata(String ip, String userAgent, String requestId) {

    public static final RequestMetadata NONE = new RequestMetadata(null, null, null);

    public static RequestMetadata current() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return new RequestMetadata(null, null, requestId);
        }
        HttpServletRequest request = attrs.getRequest();
        return new RequestMetadata(
                truncate(request.getRemoteAddr(), 64), truncate(request.getHeader("User-Agent"), 512), requestId);
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
