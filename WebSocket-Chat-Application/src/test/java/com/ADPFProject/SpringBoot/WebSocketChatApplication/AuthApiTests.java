package com.ADPFProject.SpringBoot.WebSocketChatApplication;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "app.admin.username=chat-admin",
        "app.admin.password=admin-password-1"
})
@AutoConfigureMockMvc
class AuthApiTests {

    private static final String PASSWORD = "correct-horse-1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerThenLoginReturnsBearerToken() throws Exception {
        String username = uniqueUsername();

        register(username, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username));

        login(username, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    void registerRejectsDuplicateUsernameCaseInsensitively() throws Exception {
        String username = uniqueUsername();
        register(username, PASSWORD).andExpect(status().isCreated());

        register(username.toUpperCase(), PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Username is already taken"));
    }

    @Test
    void registerValidatesInput() throws Exception {
        register("a!", "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @ParameterizedTest
    @MethodSource("passwordsAt72Utf8Bytes")
    void registerAndLoginAcceptPasswordAt72Utf8Bytes(String password) throws Exception {
        String username = uniqueUsername();

        register(username, password)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username));

        login(username, password)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @ParameterizedTest
    @MethodSource("passwordsExceeding72Utf8Bytes")
    void registerRejectsPasswordExceeding72Utf8BytesWithoutCreatingAccount(String password) throws Exception {
        String username = uniqueUsername();

        register(username, password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"))
                .andExpect(jsonPath("$.errors.password").value("must be at most 72 UTF-8 bytes"));

        login(username, password)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        register(username, PASSWORD).andExpect(status().isCreated());
    }

    @Test
    void loginFailureDoesNotRevealWhetherUserExists() throws Exception {
        String username = uniqueUsername();
        register(username, PASSWORD).andExpect(status().isCreated());

        login(username, "wrong-password-1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));
        login(uniqueUsername(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));
    }

    @Test
    void protectedEndpointRequiresBearerToken() throws Exception {
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
    }

    @Test
    void validTokenIdentifiesUserAndRoles() throws Exception {
        String username = uniqueUsername();
        register(username, PASSWORD).andExpect(status().isCreated());
        String token = accessToken(username, PASSWORD);

        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Instant now = Instant.now();
        String token = encode(jwtEncoder, claims("websocket-chat",
                now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS)));

        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        JwtEncoder forger = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        Instant now = Instant.now();
        String token = encode(forger, claims("websocket-chat", now, now.plus(1, ChronoUnit.HOURS)));

        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        Instant now = Instant.now();
        String token = encode(jwtEncoder, claims("some-other-service", now, now.plus(1, ChronoUnit.HOURS)));

        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointForbidsRegularUsers() throws Exception {
        String username = uniqueUsername();
        register(username, PASSWORD).andExpect(status().isCreated());
        String token = accessToken(username, PASSWORD);

        mvc.perform(get("/api/admin/active-users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpointAllowsAdmins() throws Exception {
        String token = accessToken("chat-admin", "admin-password-1");

        mvc.perform(get("/api/admin/active-users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    private static Stream<String> passwordsAt72Utf8Bytes() {
        return Stream.of("a".repeat(72), "é".repeat(36), "😀".repeat(18));
    }

    private static Stream<String> passwordsExceeding72Utf8Bytes() {
        return Stream.of("a".repeat(73), "é".repeat(37), "😀".repeat(19));
    }

    private ResultActions register(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private String accessToken(String username, String password) throws Exception {
        String body = login(username, password)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private static JwtClaimsSet claims(String issuer, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("someone")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("roles", List.of("USER"))
                .build();
    }

    private static String encode(JwtEncoder encoder, JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static String uniqueUsername() {
        return "u_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
