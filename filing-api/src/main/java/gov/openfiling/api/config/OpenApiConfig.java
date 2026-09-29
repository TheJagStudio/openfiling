package gov.openfiling.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER = "bearerAuth";
    public static final String INTERNAL = "internalToken";

    @Bean
    OpenAPI filingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OpenFiling Filing API")
                        .version("v1")
                        .description("""
                                Patent application intake and document management (direct-to-S3 uploads via \
                                pre-signed URLs), a TSDR-style status tracker with an examiner workflow, \
                                notifications, audit log and automated PII/CUI redaction of public documents.""")
                        .license(new License().name("Apache-2.0")))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/v1/auth/login (15 min; renew with /auth/refresh)"))
                        .addSecuritySchemes(INTERNAL, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Token")
                                .description("Shared secret for storage-event and redaction callbacks")))
                .tags(List.of(
                        new Tag().name("Auth").description("Registration, login, token refresh and logout"),
                        new Tag().name("Account").description("Profile, password and sessions of the signed-in user"),
                        new Tag().name("Applications").description("My patent applications, submission and workflow"),
                        new Tag().name("Documents").description("Direct-to-S3 uploads, downloads and redaction"),
                        new Tag().name("Dashboard").description("Role-aware dashboard"),
                        new Tag().name("Examiner").description("Examination queue and statistics (EXAMINER/ADMIN)"),
                        new Tag().name("Notifications").description("In-app notifications"),
                        new Tag().name("Admin").description("Users, audit log and system status (ADMIN)"),
                        new Tag().name("Public").description("Published applications and public copies (anonymous)"),
                        new Tag().name("Reference").description("Enumerations and limits"),
                        new Tag().name("Internal").description("Service-to-service callbacks (X-Internal-Token)")));
    }
}
