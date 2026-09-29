package gov.openfiling.api.config;

import gov.openfiling.api.storage.DocumentStorage;
import gov.openfiling.api.storage.S3DocumentStorage;

import java.net.URI;
import java.time.Clock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * AWS SDK v2 clients. With {@code app.storage.endpoint} set (dev) both clients target the local
 * emulator with path-style addressing and static credentials; with a blank endpoint (AWS) they use
 * the regional S3 endpoint and the default credentials chain (ECS task role).
 * <p>The presigner uses {@code app.storage.public-endpoint} when set, because pre-signed URLs must be
 * resolvable by the browser (e.g. {@code localhost:9000}) while the API may reach the emulator by a
 * container host name.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(AppProperties.Storage props) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(java.time.Duration.ofSeconds(3))
                        .socketTimeout(java.time.Duration.ofSeconds(30)));
        if (props.hasEndpointOverride()) {
            // The emulator does not implement aws-chunked uploads or flexible checksums.
            builder.endpointOverride(URI.create(props.endpoint()))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(props.pathStyle())
                            .chunkedEncodingEnabled(false)
                            .build())
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        } else if (props.pathStyle()) {
            builder.forcePathStyle(true);
        }
        log.info("S3 client configured: {}", props);
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(AppProperties.Storage props) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
        String endpoint = props.presignEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    DocumentStorage documentStorage(S3Client s3Client, S3Presigner s3Presigner, Clock clock) {
        return new S3DocumentStorage(s3Client, s3Presigner, clock);
    }

    static AwsCredentialsProvider credentials(AppProperties.Storage props) {
        if (props.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
