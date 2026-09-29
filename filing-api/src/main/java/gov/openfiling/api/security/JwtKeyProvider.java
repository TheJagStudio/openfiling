package gov.openfiling.api.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import gov.openfiling.api.config.AppProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RSA signing key for issued JWTs. Persisted as PKCS#8 PEM under {@code app.jwt.key-dir} and
 * generated on first start, so restarts keep previously issued tokens valid. The key id is the
 * RFC 7638 thumbprint, which keeps the {@code kid} stable across restarts.
 */
@Slf4j
@Component
public class JwtKeyProvider {

    static final String PRIVATE_KEY_FILE = "openfiling-jwt-private.pem";
    static final String PUBLIC_KEY_FILE = "openfiling-jwt-public.pem";

    private final RSAKey signingKey;

    public JwtKeyProvider(AppProperties.Jwt props) {
        this.signingKey = loadOrCreate(props.keyPath());
    }

    public RSAKey signingKey() {
        return signingKey;
    }

    public RSAPublicKey publicKey() {
        try {
            return signingKey.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public JWKSet publicJwkSet() {
        return new JWKSet(signingKey.toPublicJWK());
    }

    static RSAKey loadOrCreate(Path dir) {
        try {
            Files.createDirectories(dir);
            Path privateFile = dir.resolve(PRIVATE_KEY_FILE);
            if (!Files.exists(privateFile)) {
                generate(dir, privateFile);
            }
            RSAPrivateCrtKey privateKey = readPrivateKey(privateFile);
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            RSAKey key = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint()
                    .build();
            Path publicFile = dir.resolve(PUBLIC_KEY_FILE);
            if (!Files.exists(publicFile)) {
                Files.writeString(publicFile, pem("PUBLIC KEY", publicKey.getEncoded()), StandardCharsets.US_ASCII);
            }
            log.info("JWT signing key loaded from {} (kid={})", privateFile.toAbsolutePath().normalize(), key.getKeyID());
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load or create the JWT key in " + dir.toAbsolutePath(), e);
        } catch (GeneralSecurityException | JOSEException e) {
            throw new IllegalStateException("Invalid JWT key material in " + dir.toAbsolutePath(), e);
        }
    }

    private static void generate(Path dir, Path privateFile) throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
        Path tmp = Files.createTempFile(dir, "jwt", ".tmp");
        try {
            Files.writeString(tmp, pem("PRIVATE KEY", encoded), StandardCharsets.US_ASCII);
            restrictPermissions(tmp);
            Files.move(tmp, privateFile, StandardCopyOption.ATOMIC_MOVE);
            log.info("Generated new RSA-2048 JWT signing key at {}", privateFile.toAbsolutePath().normalize());
        } catch (FileAlreadyExistsException raced) {
            log.debug("Another process created the JWT key first; using it");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static RSAPrivateCrtKey readPrivateKey(Path file) throws IOException, GeneralSecurityException {
        String pem = Files.readString(file, StandardCharsets.US_ASCII)
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system (Windows): rely on the directory ACLs.
        }
    }
}
