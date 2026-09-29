package gov.openfiling.s3.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Independent-enough SigV4 signer for tests that need hand-crafted requests (the integration tests use the real
 * AWS SDK for everything else).
 */
public final class TestSigner {

    private TestSigner() {
    }

    /** Returns the headers to add (Authorization, x-amz-date, x-amz-content-sha256) for a header-signed request. */
    public static Map<String, String> sign(String method, String rawPath, String rawQuery, Map<String, String> headers,
                                           String payloadHash, String accessKey, String secret, String region,
                                           Instant when) {
        String amzDate = SigV4Verifier.AMZ_DATE.format(when);
        String date = amzDate.substring(0, 8);
        TreeMap<String, List<String>> all = new TreeMap<>();
        headers.forEach((k, v) -> all.put(k.toLowerCase(Locale.ROOT), List.of(v)));
        all.put("x-amz-date", List.of(amzDate));
        all.put("x-amz-content-sha256", List.of(payloadHash));
        List<String> signed = new ArrayList<>(all.keySet());
        String canonical = SigV4.canonicalRequest(method, SigV4.canonicalUri(rawPath),
                SigV4.canonicalQuery(SigV4.parseQuery(rawQuery), java.util.Set.of()),
                SigV4.canonicalHeaders(all, signed), String.join(";", signed), payloadHash);
        String scope = SigV4.scope(date, region, "s3");
        String signature = SigV4.signature(SigV4.signingKey(secret, date, region, "s3"),
                SigV4.stringToSign(amzDate, scope, canonical));
        return Map.of(
                "Authorization", SigV4.ALGORITHM + " Credential=" + accessKey + "/" + scope + ", SignedHeaders="
                        + String.join(";", signed) + ", Signature=" + signature,
                "x-amz-date", amzDate,
                "x-amz-content-sha256", payloadHash);
    }
}
