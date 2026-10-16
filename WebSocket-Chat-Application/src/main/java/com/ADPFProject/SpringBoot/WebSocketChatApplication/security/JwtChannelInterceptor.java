package com.ADPFProject.SpringBoot.WebSocketChatApplication.security;

import java.util.function.Predicate;

import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.stereotype.Component;

/**
 * Secures inbound STOMP frames.
 *
 * <ul>
 *   <li>CONNECT must carry {@code Authorization: Bearer <jwt>}; the validated token becomes the session principal.</li>
 *   <li>SUBSCRIBE and SEND require an authenticated session.</li>
 *   <li>Clients may only subscribe to the public topic and may only SEND to {@code /app/**} handlers.
 *       Sending straight to {@code /topic/**} would bypass the controller and let a client forge the sender.</li>
 * </ul>
 */
@Component
public class JwtChannelInterceptor implements ChannelInterceptor {

    public static final String PUBLIC_TOPIC = "/topic/public";
    private static final String APP_PREFIX = "/app/";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthenticationProvider jwtAuthenticationProvider;

    public JwtChannelInterceptor(JwtDecoder jwtDecoder, JwtAuthenticationConverter jwtAuthenticationConverter) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(jwtDecoder);
        provider.setJwtAuthenticationConverter(jwtAuthenticationConverter);
        this.jwtAuthenticationProvider = provider;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // heartbeats and non-STOMP messages
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> accessor.setUser(authenticate(accessor));
            case SUBSCRIBE -> {
                requireAuthenticated(accessor);
                requireDestination(accessor, PUBLIC_TOPIC::equals);
            }
            case SEND -> {
                requireAuthenticated(accessor);
                requireDestination(accessor, destination -> destination.startsWith(APP_PREFIX));
            }
            default -> {
                // UNSUBSCRIBE, DISCONNECT, ACK, NACK need no extra checks
            }
        }
        return message;
    }

    private Authentication authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new BadCredentialsException("Missing bearer token");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        // Verifies signature, expiry and issuer; throws an AuthenticationException when invalid.
        return jwtAuthenticationProvider.authenticate(new BearerTokenAuthenticationToken(token));
    }

    private static void requireAuthenticated(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new AccessDeniedException("STOMP session is not authenticated");
        }
    }

    private static void requireDestination(StompHeaderAccessor accessor, Predicate<String> allowed) {
        String destination = accessor.getDestination();
        if (destination == null || !allowed.test(destination)) {
            throw new AccessDeniedException("Destination not allowed: " + destination);
        }
    }
}
