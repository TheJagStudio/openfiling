package gov.openfiling.api.storage;

import gov.openfiling.api.exception.UpstreamUnavailableException;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * AWS SDK v2 implementation: {@link S3Presigner} for browser PUT/GET URLs, {@link S3Client} for
 * HeadObject, ranged GetObject, DeleteObject and HeadBucket. Works unchanged against Amazon S3 and
 * the local SigV4-verifying emulator (endpoint override + path-style, see {@code StorageConfig}).
 */
@Slf4j
public class S3DocumentStorage implements DocumentStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final Clock clock;

    public S3DocumentStorage(S3Client s3, S3Presigner presigner, Clock clock) {
        this.s3 = s3;
        this.presigner = presigner;
        this.clock = clock;
    }

    @Override
    public PresignedUrl presignUpload(String bucket, String key, String contentType, Duration ttl) {
        // Content-Type is set on the request, so it becomes a signed header: the browser must send exactly it.
        PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(put).build());
        return new PresignedUrl(presigned.url().toString(), "PUT", Map.of("Content-Type", contentType),
                presigned.expiration() != null ? presigned.expiration() : clock.instant().plus(ttl));
    }

    @Override
    public PresignedUrl presignDownload(String bucket, String key, String fileName, String contentType, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentDisposition(contentDisposition(fileName))
                .responseContentType(contentType)
                .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get).build());
        return new PresignedUrl(presigned.url().toString(), "GET", Map.of(),
                presigned.expiration() != null ? presigned.expiration() : clock.instant().plus(ttl));
    }

    @Override
    public Optional<ObjectHead> head(String bucket, String key) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new ObjectHead(
                    head.contentLength() == null ? 0 : head.contentLength(),
                    head.contentType(),
                    stripQuotes(head.eTag()),
                    head.lastModified()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable("HeadObject", e);
        } catch (SdkException e) {
            throw unavailable("HeadObject", e);
        }
    }

    @Override
    public byte[] readPrefix(String bucket, String key, int length) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .range("bytes=0-" + (length - 1))
                            .build())
                    .asByteArray();
        } catch (S3Exception e) {
            if (e.statusCode() == 416) {
                return new byte[0]; // empty object: the range is not satisfiable
            }
            if (e.statusCode() == 404) {
                return new byte[0];
            }
            throw unavailable("GetObject", e);
        } catch (SdkException e) {
            throw unavailable("GetObject", e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            log.debug("Object already absent: {}/{}", bucket, key);
        } catch (SdkException e) {
            throw unavailable("DeleteObject", e);
        }
    }

    @Override
    public BucketHealth bucketHealth(String bucket) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return new BucketHealth(true, "Bucket " + bucket + " reachable");
        } catch (S3Exception e) {
            return new BucketHealth(false, "Bucket " + bucket + ": HTTP " + e.statusCode());
        } catch (SdkException e) {
            return new BucketHealth(false, "Bucket " + bucket + " unreachable: " + e.getClass().getSimpleName());
        }
    }

    /** RFC 6266 attachment with an ASCII fallback and an RFC 5987 UTF-8 name. */
    static String contentDisposition(String fileName) {
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\\", "_").replace("\"", "'");
        String encoded = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private static String stripQuotes(String eTag) {
        return eTag == null ? null : eTag.replace("\"", "");
    }

    private static UpstreamUnavailableException unavailable(String operation, SdkException e) {
        log.warn("S3 {} failed: {}", operation, e.getMessage());
        return new UpstreamUnavailableException("Document storage is unavailable; try again shortly", e);
    }
}
