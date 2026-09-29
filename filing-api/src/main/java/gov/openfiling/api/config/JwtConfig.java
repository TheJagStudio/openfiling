package gov.openfiling.api.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import gov.openfiling.api.security.JwtKeyProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Self-issued RS256 tokens: encoder with the persisted private key, decoder with its public half. */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    public static final String ROLES_CLAIM = "roles";
    public static final String EMAIL_CLAIM = "email";
    public static final String NAME_CLAIM = "name";

    @Bean
    JwtEncoder jwtEncoder(JwtKeyProvider keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeyProvider keys, AppProperties.Jwt props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }
}
