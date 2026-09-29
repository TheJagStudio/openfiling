package gov.openfiling.redaction.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import gov.openfiling.redaction.RedactionConfig;

/**
 * AWS SDK v2 implementation. Locally ({@code S3_ENDPOINT} set) it is configured exactly like filing-api for the
 * SigV4-verifying emulator: endpoint override, path-style, static credentials, no chunked encoding, checksums only
 * when required. In AWS ({@code S3_ENDPOINT} unset) it uses the default credential chain (the execution role).
 */
public class S3ObjectStore implements ObjectStore, AutoCloseable {

    private final S3Client s3;

    public S3ObjectStore(RedactionConfig config) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(config.s3Region()))
                .httpClient(UrlConnectionHttpClient.create())
                .forcePathStyle(config.s3PathStyle());
        if (config.s3Endpoint() != null && !config.s3Endpoint().isBlank()) {
            builder.endpointOverride(URI.create(config.s3Endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(config.s3AccessKey(), config.s3SecretKey())))
                    .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        }
        this.s3 = builder.build();
    }

    @Override
    public long download(String bucket, String key, Path target, long maxBytes) throws IOException {
        try {
            HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key(key));
            if (head.contentLength() > maxBytes) {
                throw new ObjectTooLargeException(head.contentLength(), maxBytes);
            }
            try (ResponseInputStream<GetObjectResponse> in = s3.getObject(b -> b.bucket(bucket).key(key));
                 OutputStream out = Files.newOutputStream(target)) {
                return copyLimited(in, out, maxBytes);
            }
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException("object not found");
        }
    }

    private static long copyLimited(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new ObjectTooLargeException(total, maxBytes);
            }
            out.write(buffer, 0, n);
        }
        return total;
    }

    @Override
    public void upload(String bucket, String key, Path source, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromFile(source));
    }

    @Override
    public void upload(String bucket, String key, byte[] content, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
    }

    @Override
    public void close() {
        s3.close();
    }
}
