package com.ADPFProject.SpringBoot.WebSocketChatApplication.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_.-]{3,20}$",
                message = "must be 3-20 characters using letters, digits, '.', '_' or '-'")
        String username,

        @NotBlank
        @Size(min = 8, message = "must be at least 8 characters")
        @MaxUtf8Bytes(72)
        String password) {
}
