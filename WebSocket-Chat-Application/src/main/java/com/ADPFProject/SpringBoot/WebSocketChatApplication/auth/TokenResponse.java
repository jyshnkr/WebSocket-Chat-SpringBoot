package com.ADPFProject.SpringBoot.WebSocketChatApplication.auth;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
