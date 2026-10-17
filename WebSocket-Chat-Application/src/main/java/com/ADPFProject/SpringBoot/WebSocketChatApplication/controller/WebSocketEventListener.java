package com.ADPFProject.SpringBoot.WebSocketChatApplication.controller;

import java.security.Principal;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.presence.ActiveUserRegistry;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.security.WebSocketSessionLifetime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final ActiveUserRegistry activeUsers;
    private final WebSocketSessionLifetime lifetime;

    public WebSocketEventListener(ActiveUserRegistry activeUsers, WebSocketSessionLifetime lifetime) {
        this.activeUsers = activeUsers;
        this.lifetime = lifetime;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        Principal user = event.getUser();
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        if (user != null && sessionId != null) {
            activeUsers.connected(sessionId, user.getName(), () -> lifetime.isActive(sessionId));
            logger.info("WebSocket session connected for user {}", user.getName());
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        lifetime.disconnected(event.getSessionId());
        activeUsers.disconnected(event.getSessionId());
    }

}
