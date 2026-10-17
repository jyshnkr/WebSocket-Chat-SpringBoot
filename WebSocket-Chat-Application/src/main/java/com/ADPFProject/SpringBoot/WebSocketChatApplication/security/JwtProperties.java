package com.ADPFProject.SpringBoot.WebSocketChatApplication.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT settings bound from {@code app.jwt.*}.
 *
 * @param secret Base64-encoded HMAC key (at least 32 bytes); blank means "generate an ephemeral key"
 * @param issuer value of the {@code iss} claim, validated on every incoming token
 * @param ttl    access-token lifetime
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("websocket-chat") String issuer,
        @DefaultValue("1h") Duration ttl) {
}
