package gov.openfiling.s3.auth;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The parts of an HTTP request that SigV4 covers.
 *
 * @param method  HTTP method
 * @param rawPath request path exactly as received (still percent-encoded)
 * @param rawQuery raw query string without {@code ?} (may be null)
 * @param headers header values keyed by <em>lower-case</em> header name
 */
public record SignableRequest(String method, String rawPath, String rawQuery,
                              Map<String, ? extends Collection<String>> headers) {

    public String header(String lowerCaseName) {
        Collection<String> values = headers.get(lowerCaseName);
        return values == null || values.isEmpty() ? null : values.iterator().next();
    }

    public List<Map.Entry<String, String>> queryParams() {
        return SigV4.parseQuery(rawQuery);
    }
}
