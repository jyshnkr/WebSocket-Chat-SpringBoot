package com.ADPFProject.SpringBoot.WebSocketChatApplication.auth;

import java.util.Map;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.security.TokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserAccountService accounts;
    private final TokenService tokens;

    public AuthController(AuthenticationManager authenticationManager,
                          UserAccountService accounts,
                          TokenService tokens) {
        this.authenticationManager = authenticationManager;
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> register(@Valid @RequestBody RegisterRequest request) {
        accounts.register(request.username(), request.password());
        return Map.of("username", request.username());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        TokenService.IssuedToken token = tokens.issue(authentication);
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
    }
}
