package gov.openfiling.api.service;

import gov.openfiling.api.config.AppProperties;
import gov.openfiling.api.model.RefreshToken;
import gov.openfiling.api.model.User;
import gov.openfiling.api.repository.RefreshTokenRepository;
import gov.openfiling.api.security.RequestMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates opaque 256-bit refresh tokens; only their SHA-256 (hex) is stored. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final AppProperties.Jwt props;
    private final Clock clock;

    public record Issued(String rawToken, RefreshToken token) {

        @Override
        public String toString() {
            return "Issued[token=" + token + "]";
        }
    }

    /** Issues a token in {@code familyId} (a new login passes a fresh family id). */
    @Transactional
    public Issued issue(User user, UUID familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        RequestMetadata request = RequestMetadata.current();
        RefreshToken token = new RefreshToken();
        token.setId(UUID.randomUUID());
        token.setUser(user);
        token.setFamilyId(familyId);
        token.setTokenHash(hash(raw));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(props.refreshTokenTtl()));
        token.setUserAgent(request.userAgent());
        token.setIp(request.ip());
        tokens.save(token);
        return new Issued(raw, token);
    }

    public long ttlSeconds() {
        return props.refreshTokenTtl().toSeconds();
    }

    public static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
