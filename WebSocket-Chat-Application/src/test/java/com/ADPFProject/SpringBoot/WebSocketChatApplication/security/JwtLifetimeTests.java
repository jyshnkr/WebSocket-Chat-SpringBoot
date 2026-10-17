package com.ADPFProject.SpringBoot.WebSocketChatApplication.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.MutableSecurityClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

class JwtLifetimeTests {

    private static final Instant EXPIRY = Instant.parse("2026-10-16T12:00:00Z");
    private final MutableSecurityClock clock = new MutableSecurityClock(EXPIRY);
    private final JwtConfig config = new JwtConfig();
    private final SecretKey key = new SecretKeySpec(new byte[32], "HmacSHA256");
    private final JwtProperties properties = new JwtProperties(null, "websocket-chat", Duration.ofHours(1));
    private final JwtEncoder encoder = config.jwtEncoder(key);
    private final JwtDecoder decoder = config.jwtDecoder(key, properties, clock, new JwtLifetimePolicy(clock));

    @Test
    void tokensWithoutAnExpiryAreRejected() {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("websocket-chat")
                .subject("alice").issuedAt(EXPIRY.minusSeconds(120)).build();
        String token = encode(claims);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void tokenIsValidUntilTheEndOfTheExistingSixtySecondGrace() {
        clock.set(EXPIRY.plusSeconds(60).minusNanos(1));

        assertThat(decoder.decode(encode(claims().build())).getSubject()).isEqualTo("alice");
    }

    @ParameterizedTest
    @ValueSource(ints = {60, 61})
    void tokenIsInvalidAtAndAfterTheDeadline(int secondsAfterExpiry) {
        clock.set(EXPIRY.plusSeconds(secondsAfterExpiry));
        String token = encode(claims().build());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void issuerValidationIsPreserved() {
        String token = encode(claims().issuer("another-issuer").build());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void notBeforeValidationUsesTheSameClockAndGrace() {
        String token = encode(claims().expiresAt(EXPIRY.plusSeconds(300))
                .notBefore(EXPIRY.plusSeconds(61)).build());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
        clock.set(EXPIRY.plusSeconds(1));
        assertThat(decoder.decode(token).getSubject()).isEqualTo("alice");
    }

    @Test
    void issuanceUsesTheInjectedClock() {
        TokenService service = new TokenService(encoder, properties, clock);
        String token = service.issue(UsernamePasswordAuthenticationToken.authenticated(
                "alice", null, AuthorityUtils.createAuthorityList("ROLE_USER"))).value();

        assertThat(decoder.decode(token).getIssuedAt()).isEqualTo(EXPIRY);
        assertThat(decoder.decode(token).getExpiresAt()).isEqualTo(Instant.parse("2026-10-16T13:00:00Z"));
    }

    private JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder().issuer("websocket-chat").subject("alice")
                .issuedAt(EXPIRY.minusSeconds(120)).expiresAt(EXPIRY);
    }

    private String encode(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
