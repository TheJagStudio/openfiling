package gov.openfiling.s3.auth;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure AWS Signature Version 4 primitives (no I/O, no clock). Follows the S3 flavour of SigV4:
 * the canonical URI is the path URI-encoded <em>once</em> (S3 does not double-encode, and keeps {@code /}).
 */
public final class SigV4 {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final HexFormat HEX = HexFormat.of();
    private static final char[] UPPER_HEX = "0123456789ABCDEF".toCharArray();

    private SigV4() {
    }

    /**
     * RFC 3986 encoding as required by SigV4: every byte except the unreserved characters
     * {@code A-Z a-z 0-9 - _ . ~} is percent-encoded with upper-case hex; {@code /} is kept when {@code keepSlash}.
     */
    public static String uriEncode(String value, boolean keepSlash) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 16);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (keepSlash && c == '/')) {
                out.append(c);
            } else {
                out.append('%').append(UPPER_HEX[(b >> 4) & 0xF]).append(UPPER_HEX[b & 0xF]);
            }
        }
        return out.toString();
    }

    /** Percent-decodes (UTF-8). {@code +} is kept literally (it is not a space in a path or a SigV4 query). */
    public static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        int i = 0;
        while (i < value.length()) {
            if (value.charAt(i) == '%') {
                if (i + 2 >= value.length()) {
                    throw new IllegalArgumentException("Malformed percent-encoding");
                }
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("Malformed percent-encoding");
                }
                out.write((hi << 4) | lo);
                i += 3;
            } else {
                int next = value.indexOf('%', i);
                int end = next < 0 ? value.length() : next;
                out.writeBytes(value.substring(i, end).getBytes(StandardCharsets.UTF_8));
                i = end;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Canonical URI for S3: decode the raw request path once, then re-encode it with SigV4 rules. */
    public static String canonicalUri(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        return uriEncode(percentDecode(rawPath), true);
    }

    /** Parses a raw query string into decoded (name, value) pairs, preserving duplicates and order. */
    public static List<Map.Entry<String, String>> parseQuery(String rawQuery) {
        List<Map.Entry<String, String>> params = new ArrayList<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String name = eq < 0 ? part : part.substring(0, eq);
            String value = eq < 0 ? "" : part.substring(eq + 1);
            params.add(Map.entry(percentDecode(name), percentDecode(value)));
        }
        return params;
    }

    /** Canonical query string: every parameter (except {@code excluded}) encoded, then sorted by name and value. */
    public static String canonicalQuery(List<Map.Entry<String, String>> params, Set<String> excluded) {
        return params.stream()
                .filter(e -> !excluded.contains(e.getKey()))
                .map(e -> new String[] {uriEncode(e.getKey(), false), uriEncode(e.getValue(), false)})
                .sorted((a, b) -> {
                    int byName = a[0].compareTo(b[0]);
                    return byName != 0 ? byName : a[1].compareTo(b[1]);
                })
                .map(p -> p[0] + "=" + p[1])
                .collect(Collectors.joining("&"));
    }

    /**
     * Canonical headers block (each line terminated by {@code \n}) for the signed header names, which must
     * already be lower-case. Values are trimmed, inner whitespace runs collapsed, multiple values joined by {@code ,}.
     */
    public static String canonicalHeaders(Map<String, ? extends Collection<String>> lowerCaseHeaders,
                                          List<String> signedHeaders) {
        StringBuilder sb = new StringBuilder();
        for (String name : signedHeaders) {
            Collection<String> values = lowerCaseHeaders.get(name);
            String joined = values == null ? "" : values.stream()
                    .map(SigV4::normalizeHeaderValue)
                    .collect(Collectors.joining(","));
            sb.append(name).append(':').append(joined).append('\n');
        }
        return sb.toString();
    }

    static String normalizeHeaderValue(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    public static String canonicalRequest(String method, String canonicalUri, String canonicalQuery,
                                          String canonicalHeaders, String signedHeaders, String payloadHash) {
        return method.toUpperCase(Locale.ROOT) + '\n'
                + canonicalUri + '\n'
                + canonicalQuery + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + payloadHash;
    }

    public static String scope(String date, String region, String service) {
        return date + '/' + region + '/' + service + "/aws4_request";
    }

    public static String stringToSign(String amzDate, String scope, String canonicalRequest) {
        return ALGORITHM + '\n' + amzDate + '\n' + scope + '\n'
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] signingKey(String secretKey, String date, String region, String service) {
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, "aws4_request");
    }

    public static String signature(byte[] signingKey, String stringToSign) {
        return HEX.formatHex(hmac(signingKey, stringToSign));
    }

    public static String sha256Hex(byte[] data) {
        return HEX.formatHex(sha256().digest(data));
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison of two signatures (hex strings compared case-insensitively). */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
