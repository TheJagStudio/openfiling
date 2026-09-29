package gov.openfiling.api.controller;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.config.SecurityConfig;
import gov.openfiling.api.security.ProblemResponseWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Security + problem-writer wiring shared by the {@code @WebMvcTest} slices. */
@TestConfiguration
@Import({SecurityConfig.class, ProblemResponseWriter.class})
@EnableConfigurationProperties({AppProperties.Security.class, AppProperties.Cors.class})
class WebSliceConfig {

}
