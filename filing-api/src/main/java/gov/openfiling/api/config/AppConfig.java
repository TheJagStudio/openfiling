package gov.openfiling.api.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;

/** Kept off the application class so test slices do not inherit property scanning. */
@Configuration(proxyBeanMethods = false)
@ConfigurationPropertiesScan(basePackageClasses = AppProperties.class)
public class AppConfig {
}
