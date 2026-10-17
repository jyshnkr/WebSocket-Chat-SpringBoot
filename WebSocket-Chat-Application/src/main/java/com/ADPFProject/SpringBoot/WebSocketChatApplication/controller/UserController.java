package com.ADPFProject.SpringBoot.WebSocketChatApplication.controller;

import java.util.List;
import java.util.Objects;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.presence.ActiveUserRegistry;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.security.TokenService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class UserController {

    private final ActiveUserRegistry activeUsers;

    public UserController(ActiveUserRegistry activeUsers) {
        this.activeUsers = activeUsers;
    }

    /** Any authenticated user: who am I, and which roles does my token carry? */
    @GetMapping("/users/me")
    public CurrentUser me(@AuthenticationPrincipal Jwt jwt) {
        List<String> roles = Objects.requireNonNullElse(jwt.getClaimAsStringList(TokenService.ROLES_CLAIM), List.of());
        return new CurrentUser(jwt.getSubject(), roles);
    }

    /** Admins only: users with an open chat session. */
    @GetMapping("/admin/active-users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<String> activeUsers() {
        return activeUsers.usernames();
    }

    public record CurrentUser(String username, List<String> roles) {
    }
}
