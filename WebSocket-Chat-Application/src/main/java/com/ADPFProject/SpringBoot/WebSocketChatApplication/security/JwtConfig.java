package com.ADPFProject.SpringBoot.WebSocketChatApplication.security;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/**
 * Signs and verifies access tokens with HMAC-SHA256.
 */
@Configuration
public class JwtConfig {

    private static final Logger logger = LoggerFactory.getLogger(JwtConfig.class);

    /** HS256 requires a key of at least 256 bits. */
    static final int MIN_KEY_BYTES = 32;

    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        byte[] keyBytes;
        if (!StringUtils.hasText(properties.secret())) {
            keyBytes = new byte[MIN_KEY_BYTES];
            new SecureRandom().nextBytes(keyBytes);
            logger.warn("app.jwt.secret is not set; using an ephemeral signing key. "
                    + "Issued tokens will stop working after a restart.");
        } else {
            try {
                keyBytes = Base64.getDecoder().decode(properties.secret().trim());
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("app.jwt.secret must be Base64-encoded", ex);
            }
            if (keyBytes.length < MIN_KEY_BYTES) {
                throw new IllegalStateException(
                        "app.jwt.secret must decode to at least " + MIN_KEY_BYTES + " bytes for HS256");
            }
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Signature is checked by the decoder; this adds exp/nbf (with clock skew) and issuer checks.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}
