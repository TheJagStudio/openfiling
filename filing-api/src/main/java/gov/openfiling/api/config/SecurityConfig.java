package gov.openfiling.api.config;

import gov.openfiling.api.security.InternalTokenFilter;
import gov.openfiling.api.security.ProblemResponseWriter;
import jakarta.servlet.DispatcherType;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Two stateless chains:
 * <ol>
 *   <li>{@code /internal/**} &mdash; shared-secret header only (storage-event and redaction callbacks).</li>
 *   <li>everything else &mdash; auth, public and reference endpoints are anonymous; examiner routes need
 *       EXAMINER/ADMIN, admin routes ADMIN, the rest any authenticated user. Fine-grained ownership and
 *       visibility rules live in the services.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Anonymous endpoints ignore any (possibly expired) bearer token the SPA attaches. */
    private static final List<String> TOKENLESS_PREFIXES = List.of("/api/v1/public/", "/api/v1/reference", "/api/v1/auth/register",
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout");

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** {@code roles: ["INVENTOR","EXAMINER"]} &rarr; {@code ROLE_INVENTOR, ROLE_EXAMINER}. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtConfig.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> {
            String uri = request.getRequestURI();
            for (String prefix : TOKENLESS_PREFIXES) {
                if (uri.startsWith(prefix)) {
                    return null;
                }
            }
            return delegate.resolve(request);
        };
    }

    @Bean
    AuthenticationEntryPoint problemAuthenticationEntryPoint(ProblemResponseWriter problems) {
        return (request, response, ex) ->
                problems.write(request, response, HttpStatus.UNAUTHORIZED, "Authentication is required (Bearer token missing, invalid or expired)");
    }

    @Bean
    AccessDeniedHandler problemAccessDeniedHandler(ProblemResponseWriter problems) {
        return (request, response, ex) ->
                problems.write(request, response, HttpStatus.FORBIDDEN, "You do not have permission to access this resource");
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties.Cors cors) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(cors.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "Location"));
        config.setAllowCredentials(false);
        config.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    @Order(1)
    SecurityFilterChain internalChain(
            HttpSecurity http,
            AppProperties.Security props,
            ProblemResponseWriter problems,
            AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler deniedHandler) throws Exception {
        http.securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalTokenFilter(props.internalToken(), problems), AuthorizationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .anyRequest().hasRole(InternalTokenFilter.ROLE));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            BearerTokenResolver bearerTokenResolver,
            CorsConfigurationSource corsConfigurationSource,
            AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler deniedHandler) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(bearerTokenResolver)
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler)
                        .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**", "/api/v1/reference").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/admin/**", "/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/examiner/**").hasAnyRole("EXAMINER", "ADMIN")
                        .anyRequest().authenticated());
        return http.build();
    }
}
