package gov.openfiling.s3;

/**
 * An S3 protocol error, rendered as the S3 XML error document
 * ({@code <Error><Code/><Message/><Resource/><RequestId/></Error>}) with the given HTTP status.
 */
public class S3Exception extends RuntimeException {

    private final int status;
    private final String code;

    public S3Exception(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static S3Exception accessDenied(String message) {
        return new S3Exception(403, "AccessDenied", message);
    }

    public static S3Exception signatureDoesNotMatch() {
        return new S3Exception(403, "SignatureDoesNotMatch",
                "The request signature we calculated does not match the signature you provided. "
                        + "Check your key and signing method.");
    }

    public static S3Exception invalidAccessKeyId() {
        return new S3Exception(403, "InvalidAccessKeyId",
                "The AWS Access Key Id you provided does not exist in our records.");
    }

    public static S3Exception invalidArgument(String message) {
        return new S3Exception(400, "InvalidArgument", message);
    }

    public static S3Exception invalidRequest(String message) {
        return new S3Exception(400, "InvalidRequest", message);
    }

    public static S3Exception notImplemented(String message) {
        return new S3Exception(501, "NotImplemented", message);
    }

    public static S3Exception noSuchBucket() {
        return new S3Exception(404, "NoSuchBucket", "The specified bucket does not exist");
    }

    public static S3Exception noSuchKey() {
        return new S3Exception(404, "NoSuchKey", "The specified key does not exist.");
    }
}
