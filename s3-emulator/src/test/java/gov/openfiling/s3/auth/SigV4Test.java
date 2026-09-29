package gov.openfiling.s3.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** SigV4 primitives, checked against the published AWS SigV4 test suite ("get-vanilla" and friends). */
class SigV4Test {

    // aws-sig-v4-test-suite credentials
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

    @Test
    void getVanillaFromTheAwsTestSuite() {
        Map<String, List<String>> headers = Map.of(
                "host", List.of("example.amazonaws.com"),
                "x-amz-date", List.of("20150830T123600Z"));
        List<String> signed = List.of("host", "x-amz-date");
        String canonical = SigV4.canonicalRequest("GET", SigV4.canonicalUri("/"), SigV4.canonicalQuery(List.of(), Set.of()),
                SigV4.canonicalHeaders(headers, signed), "host;x-amz-date", SigV4.EMPTY_SHA256);
        assertThat(canonical).isEqualTo("""
                GET
                /

                host:example.amazonaws.com
                x-amz-date:20150830T123600Z

                host;x-amz-date
                e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855""");
        String scope = SigV4.scope("20150830", "us-east-1", "service");
        String sts = SigV4.stringToSign("20150830T123600Z", scope, canonical);
        assertThat(sts).isEqualTo("""
                AWS4-HMAC-SHA256
                20150830T123600Z
                20150830/us-east-1/service/aws4_request
                bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63""");
        String signature = SigV4.signature(SigV4.signingKey(SECRET, "20150830", "us-east-1", "service"), sts);
        assertThat(signature).isEqualTo("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
    }

    @Test
    void derivedSigningKeyFromTheAwsDocumentation() {
        // "Examples of how to derive a signing key for Signature Version 4" (iam, 20120215)
        byte[] key = SigV4.signingKey(SECRET, "20120215", "us-east-1", "iam");
        assertThat(java.util.HexFormat.of().formatHex(key))
                .isEqualTo("f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d");
    }

    @Test
    void uriEncodingFollowsSigV4Rules() {
        assertThat(SigV4.uriEncode("a b+c/d~e_f.g-h*", true)).isEqualTo("a%20b%2Bc/d~e_f.g-h%2A");
        assertThat(SigV4.uriEncode("a/b", false)).isEqualTo("a%2Fb");
        assertThat(SigV4.uriEncode("é", false)).isEqualTo("%C3%A9");
        assertThat(SigV4.uriEncode("$", true)).isEqualTo("%24");
    }

    @Test
    void canonicalUriDecodesOnceAndReEncodes() {
        // S3 does not double-encode: an already encoded path round-trips unchanged.
        assertThat(SigV4.canonicalUri("/bucket/my%20file%2B1.pdf")).isEqualTo("/bucket/my%20file%2B1.pdf");
        // characters sent unencoded by lenient clients are encoded as the signer would have done
        assertThat(SigV4.canonicalUri("/bucket/a(1).pdf")).isEqualTo("/bucket/a%281%29.pdf");
        assertThat(SigV4.canonicalUri("")).isEqualTo("/");
        assertThat(SigV4.canonicalUri("/%c3%a9")).isEqualTo("/%C3%A9");
    }

    @Test
    void percentDecodeRejectsMalformedInput() {
        assertThat(SigV4.percentDecode("plain+text")).isEqualTo("plain+text");
        assertThat(SigV4.percentDecode("a%2Fb%20c")).isEqualTo("a/b c");
        assertThatThrownBy(() -> SigV4.percentDecode("bad%2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SigV4.percentDecode("bad%zz")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalQuerySortsEncodesAndExcludes() {
        List<Map.Entry<String, String>> params = SigV4.parseQuery(
                "prefix=J&max-keys=2&X-Amz-Signature=abc&a=2&a=1&flag&empty=&sp=a%20b");
        assertThat(SigV4.canonicalQuery(params, Set.of("X-Amz-Signature")))
                .isEqualTo("a=1&a=2&empty=&flag=&max-keys=2&prefix=J&sp=a%20b");
        assertThat(SigV4.parseQuery(null)).isEmpty();
        assertThat(SigV4.parseQuery("&&")).isEmpty();
    }

    @Test
    void canonicalHeadersTrimAndCollapseWhitespace() {
        Map<String, List<String>> headers = Map.of("x-a", List.of("  one   two  ", "three"));
        assertThat(SigV4.canonicalHeaders(headers, List.of("x-a", "missing"))).isEqualTo("x-a:one two,three\nmissing:\n");
    }

    @Test
    void helpers() {
        assertThat(SigV4.sha256Hex(new byte[0])).isEqualTo(SigV4.EMPTY_SHA256);
        assertThat(SigV4.sha256Hex("Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072");
        assertThat(SigV4.constantTimeEquals("ABCD", "abcd")).isTrue();
        assertThat(SigV4.constantTimeEquals("abcd", "abce")).isFalse();
        assertThat(SigV4.constantTimeEquals(null, "abce")).isFalse();
    }
}
