package gov.openfiling.s3.config;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import gov.openfiling.s3.auth.SigV4Verifier;
import gov.openfiling.s3.notify.NotificationPublisher;
import gov.openfiling.s3.storage.FileSystemObjectStore;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmulatorProperties.class)
public class EmulatorConfig {

    private static final Logger log = LoggerFactory.getLogger(EmulatorConfig.class);

    /** Private mapper for sidecars and event JSON (independent of the MVC message converters). */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SigV4Verifier sigV4Verifier(EmulatorProperties properties, Clock clock) {
        return new SigV4Verifier(Map.of(properties.accessKey(), properties.secretKey()), properties.region(),
                properties.clockSkew(), clock);
    }

    @Bean
    FileSystemObjectStore objectStore(EmulatorProperties properties, Clock clock) {
        FileSystemObjectStore store = new FileSystemObjectStore(Path.of(properties.storageRoot()), properties.buckets(),
                properties.maxObjectBytes().toBytes(), JSON, clock);
        store.initialize();
        log.info("S3 emulator storage root {} with buckets {}", store.root(), store.bucketNames());
        return store;
    }

    @Bean(destroyMethod = "close")
    NotificationPublisher notificationPublisher(EmulatorProperties properties, Clock clock) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        properties.notifications().forEach(n -> log.info("Event notification '{}': {} prefix='{}' suffix='{}' -> {}",
                n.name(), n.bucket(), n.prefix(), n.suffix(), n.url()));
        return new NotificationPublisher(properties.notifications(), properties.notificationRetry(),
                properties.region(), http, JSON, clock, Executors.newVirtualThreadPerTaskExecutor());
    }
}
