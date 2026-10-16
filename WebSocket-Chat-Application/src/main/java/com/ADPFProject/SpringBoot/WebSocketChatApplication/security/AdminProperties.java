package com.ADPFProject.SpringBoot.WebSocketChatApplication.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional admin account bound from {@code app.admin.*}; seeded only when both values are set.
 */
@ConfigurationProperties("app.admin")
public record AdminProperties(String username, String password) {
}
