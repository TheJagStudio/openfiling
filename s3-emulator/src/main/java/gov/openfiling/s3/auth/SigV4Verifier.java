package gov.openfiling.s3.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import gov.openfiling.s3.S3Exception;

/**
 * Verifies AWS SigV4 signatures in both forms S3 accepts: the {@code Authorization} header and the
 * query-string (pre-signed URL) form. Enforces credential scope (date / region / {@code s3} / {@code aws4_request}),
 * expiry of pre-signed URLs, and the allowed clock skew. Signatures are compared in constant time.
 * Body hashing is <em>not</em> done here: the caller must hash the body when
 * {@link VerifiedRequest#payloadHashDeclared()} is true (the body is streamed, never buffered).
 */
public class SigV4Verifier {

    public static final String SERVICE = "s3";
    public static final long MAX_PRESIGN_EXPIRES_SECONDS = 604_800;

    static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final Pattern HEX_SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern SIGNATURE = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Set<String> PRESIGN_EXCLUDED = Set.of("X-Amz-Signature");

    private final Map<String, String> credentials;
    private final String region;
    private final Duration clockSkew;
    private final Clock clock;

    public SigV4Verifier(Map<String, String> credentials, String region, Duration clockSkew, Clock clock) {
        this.credentials = Map.copyOf(credentials);
        this.region = region;
        this.clockSkew = clockSkew;
        this.clock = clock;
    }

    public VerifiedRequest verify(SignableRequest request) {
        String authorization = request.header("authorization");
        List<Map.Entry<String, String>> query = request.queryParams();
        boolean presigned = query.stream().anyMatch(e -> e.getKey().equals("X-Amz-Algorithm")
                || e.getKey().equals("X-Amz-Signature") || e.getKey().equals("X-Amz-Credential"));
        if (authorization != null && presigned) {
            throw S3Exception.invalidArgument("Only one auth mechanism allowed; only the X-Amz-Algorithm query "
                    + "parameter, Signature query string parameter or the Authorization header should be specified");
        }
        if (authorization != null) {
            return verifyHeader(request, authorization, query);
        }
        if (presigned) {
            return verifyPresigned(request, query);
        }
        throw S3Exception.accessDenied("Anonymous access is not allowed; sign the request with AWS SigV4");
    }

    // ---------------------------------------------------------------- Authorization header

    private VerifiedRequest verifyHeader(SignableRequest request, String authorization,
                                         List<Map.Entry<String, String>> query) {
        if (!authorization.startsWith(SigV4.ALGORITHM + " ")) {
            throw S3Exception.invalidRequest(
                    "The authorization mechanism you have provided is not supported. Please use AWS4-HMAC-SHA256.");
        }
        Map<String, String> parts = new HashMap<>();
        for (String part : authorization.substring(SigV4.ALGORITHM.length() + 1).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                parts.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        String credential = parts.get("Credential");
        String signedHeaders = parts.get("SignedHeaders");
        String signature = parts.get("Signature");
        if (credential == null || signedHeaders == null || signature == null) {
            throw headerMalformed("The authorization header is malformed; it must contain Credential, "
                    + "SignedHeaders and Signature");
        }
        Scope scope = parseScope(credential, false);

        String amzDate = request.header("x-amz-date");
        if (amzDate == null) {
            throw accessDeniedMissingDate();
        }
        Instant requestTime = parseAmzDate(amzDate, false);
        checkScopeDate(scope, amzDate, false);
        Instant now = clock.instant();
        if (Duration.between(requestTime, now).abs().compareTo(clockSkew) > 0) {
            throw new S3Exception(403, "RequestTimeTooSkewed",
                    "The difference between the request time and the current time is too large.");
        }

        String payloadHash = request.header("x-amz-content-sha256");
        if (payloadHash == null) {
            throw S3Exception.invalidRequest("Missing required header for this request: x-amz-content-sha256");
        }
        payloadHash = checkPayloadHash(payloadHash);

        List<String> signed = parseSignedHeaders(signedHeaders, false);
        String canonicalRequest = SigV4.canonicalRequest(request.method(), SigV4.canonicalUri(request.rawPath()),
                SigV4.canonicalQuery(query, Set.of()), SigV4.canonicalHeaders(request.headers(), signed),
                String.join(";", signed), request.header("x-amz-content-sha256").trim());
        checkSignature(scope, amzDate, canonicalRequest, signature);
        return new VerifiedRequest(scope.accessKey(), false, payloadHash);
    }

    // ---------------------------------------------------------------- query string (pre-signed URL)

    private VerifiedRequest verifyPresigned(SignableRequest request, List<Map.Entry<String, String>> query) {
        Map<String, String> params = new HashMap<>();
        for (Map.Entry<String, String> e : query) {
            params.putIfAbsent(e.getKey(), e.getValue());
        }
        String algorithm = params.get("X-Amz-Algorithm");
        String credential = params.get("X-Amz-Credential");
        String amzDate = params.get("X-Amz-Date");
        String expires = params.get("X-Amz-Expires");
        String signedHeaders = params.get("X-Amz-SignedHeaders");
        String signature = params.get("X-Amz-Signature");
        if (algorithm == null || credential == null || amzDate == null || expires == null
                || signedHeaders == null || signature == null) {
            throw queryError("Query-string authentication version 4 requires the X-Amz-Algorithm, "
                    + "X-Amz-Credential, X-Amz-Signature, X-Amz-Date, X-Amz-SignedHeaders, and X-Amz-Expires parameters.");
        }
        if (!SigV4.ALGORITHM.equals(algorithm)) {
            throw queryError("X-Amz-Algorithm only supports \"AWS4-HMAC-SHA256\"");
        }
        Scope scope = parseScope(credential, true);
        Instant requestTime = parseAmzDate(amzDate, true);
        checkScopeDate(scope, amzDate, true);

        long expiresSeconds;
        try {
            expiresSeconds = Long.parseLong(expires);
        } catch (NumberFormatException e) {
            throw queryError("X-Amz-Expires should be a number");
        }
        if (expiresSeconds < 0) {
            throw queryError("X-Amz-Expires must be non-negative");
        }
        if (expiresSeconds > MAX_PRESIGN_EXPIRES_SECONDS) {
            throw queryError("X-Amz-Expires must be less than a week (in seconds) that is 604800");
        }
        Instant now = clock.instant();
        if (requestTime.minus(clockSkew).isAfter(now)) {
            throw S3Exception.accessDenied("Request is not valid yet");
        }
        if (now.isAfter(requestTime.plusSeconds(expiresSeconds))) {
            throw S3Exception.accessDenied("Request has expired");
        }

        String payloadHash = params.getOrDefault("X-Amz-Content-Sha256", SigV4.UNSIGNED_PAYLOAD);
        String checkedHash = checkPayloadHash(payloadHash);
        List<String> signed = parseSignedHeaders(signedHeaders, true);
        String canonicalRequest = SigV4.canonicalRequest(request.method(), SigV4.canonicalUri(request.rawPath()),
                SigV4.canonicalQuery(query, PRESIGN_EXCLUDED), SigV4.canonicalHeaders(request.headers(), signed),
                String.join(";", signed), payloadHash);
        checkSignature(scope, amzDate, canonicalRequest, signature);
        return new VerifiedRequest(scope.accessKey(), true, checkedHash);
    }

    // ---------------------------------------------------------------- shared checks

    private record Scope(String accessKey, String date, String region, String service, String terminal) {
        String value() {
            return SigV4.scope(date, region, service);
        }
    }

    private Scope parseScope(String credential, boolean presigned) {
        String[] parts = credential.split("/", -1);
        if (parts.length != 5 || Arrays.stream(parts).anyMatch(String::isEmpty)) {
            throw scopeError(presigned, "Error parsing the X-Amz-Credential parameter; the Credential is mal-formed; "
                    + "expecting \"<YOUR-AKID>/YYYYMMDD/REGION/SERVICE/aws4_request\".");
        }
        Scope scope = new Scope(parts[0], parts[1], parts[2], parts[3], parts[4]);
        if (!credentials.containsKey(scope.accessKey())) {
            throw S3Exception.invalidAccessKeyId();
        }
        if (!region.equals(scope.region())) {
            throw scopeError(presigned, "The authorization header is malformed; the region '" + scope.region()
                    + "' is wrong; expecting '" + region + "'");
        }
        if (!SERVICE.equals(scope.service())) {
            throw scopeError(presigned, "The authorization header is malformed; incorrect service \""
                    + scope.service() + "\". This endpoint belongs to \"s3\".");
        }
        if (!"aws4_request".equals(scope.terminal())) {
            throw scopeError(presigned, "The authorization header is malformed; incorrect terminal \""
                    + scope.terminal() + "\". This endpoint uses \"aws4_request\".");
        }
        return scope;
    }

    private static void checkScopeDate(Scope scope, String amzDate, boolean presigned) {
        if (!amzDate.startsWith(scope.date())) {
            throw scopeError(presigned, "The authorization header is malformed; Invalid credential date \""
                    + scope.date() + "\". This date is not the same as X-Amz-Date: \"" + amzDate.substring(0, 8) + "\".");
        }
    }

    private static Instant parseAmzDate(String amzDate, boolean presigned) {
        try {
            return LocalDateTime.parse(amzDate, AMZ_DATE).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            if (presigned) {
                throw queryError("X-Amz-Date must be in the ISO8601 Long Format \"yyyyMMdd'T'HHmmss'Z'\"");
            }
            throw accessDeniedMissingDate();
        }
    }

    private static String checkPayloadHash(String payloadHash) {
        String value = payloadHash.trim();
        if (SigV4.UNSIGNED_PAYLOAD.equals(value)) {
            return value;
        }
        if (value.startsWith("STREAMING-")) {
            throw S3Exception.notImplemented("Chunked (aws-chunked / " + value + ") uploads are not supported by the "
                    + "emulator; disable chunked encoding in the SDK");
        }
        if (!HEX_SHA256.matcher(value).matches()) {
            throw S3Exception.invalidArgument("x-amz-content-sha256 must be UNSIGNED-PAYLOAD, STREAMING-..., "
                    + "or a valid sha256 value.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static List<String> parseSignedHeaders(String signedHeaders, boolean presigned) {
        List<String> names = Arrays.stream(signedHeaders.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (names.isEmpty() || names.stream().anyMatch(n -> !n.equals(n.toLowerCase(Locale.ROOT)))) {
            throw scopeError(presigned, "SignedHeaders must be a non-empty, lower-case, ';'-separated list");
        }
        if (!names.contains("host")) {
            throw S3Exception.accessDenied("There were headers present in the request which were not signed: host");
        }
        return names;
    }

    private void checkSignature(Scope scope, String amzDate, String canonicalRequest, String provided) {
        if (!SIGNATURE.matcher(provided).matches()) {
            throw S3Exception.signatureDoesNotMatch();
        }
        String secret = credentials.get(scope.accessKey());
        byte[] key = SigV4.signingKey(secret, scope.date(), scope.region(), scope.service());
        String expected = SigV4.signature(key, SigV4.stringToSign(amzDate, scope.value(), canonicalRequest));
        if (!SigV4.constantTimeEquals(expected, provided)) {
            throw S3Exception.signatureDoesNotMatch();
        }
    }

    private static S3Exception scopeError(boolean presigned, String message) {
        return presigned ? queryError(message) : headerMalformed(message);
    }

    private static S3Exception headerMalformed(String message) {
        return new S3Exception(400, "AuthorizationHeaderMalformed", message);
    }

    private static S3Exception queryError(String message) {
        return new S3Exception(400, "AuthorizationQueryParametersError", message);
    }

    private static S3Exception accessDeniedMissingDate() {
        return S3Exception.accessDenied("AWS authentication requires a valid Date or x-amz-date header");
    }
}
