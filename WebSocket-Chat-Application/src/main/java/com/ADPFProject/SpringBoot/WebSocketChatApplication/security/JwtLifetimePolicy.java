package com.ADPFProject.SpringBoot.WebSocketChatApplication.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class JwtLifetimePolicy implements OAuth2TokenValidator<Jwt> {

    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final Clock clock;

    public JwtLifetimePolicy(Clock clock) {
        this.clock = clock;
    }

    public Instant deadline(Jwt token) {
        if (token.getExpiresAt() == null) {
            throw new IllegalArgumentException("Token must have an expiry");
        }
        return token.getExpiresAt().plus(CLOCK_SKEW);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (token.getExpiresAt() != null && clock.instant().isBefore(deadline(token))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Token is expired or has no expiry", null));
    }
}
