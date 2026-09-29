package gov.openfiling.api.service;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.config.JwtConfig;
import gov.openfiling.api.model.Role;
import gov.openfiling.api.model.User;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues RS256 access tokens: iss, sub (user id), email, name, roles, iat, exp, jti. */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtEncoder encoder;
    private final AppProperties.Jwt props;
    private final Clock clock;

    public record IssuedToken(String value, long expiresInSeconds) {

        @Override
        public String toString() {
            return "IssuedToken[expiresIn=" + expiresInSeconds + "]";
        }
    }

    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim(JwtConfig.EMAIL_CLAIM, user.getEmail())
                .claim(JwtConfig.NAME_CLAIM, user.getDisplayName())
                .claim(JwtConfig.ROLES_CLAIM, user.getRoles().stream().sorted().map(Role::name).toList())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, props.accessTokenTtl().toSeconds());
    }
}
