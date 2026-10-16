package com.ADPFProject.SpringBoot.WebSocketChatApplication.controller;

import java.security.Principal;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.model.ChatMessage;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.presence.ActiveUserRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final SimpMessageSendingOperations messagingTemplate;
    private final ActiveUserRegistry activeUsers;

    public WebSocketEventListener(SimpMessageSendingOperations messagingTemplate, ActiveUserRegistry activeUsers) {
        this.messagingTemplate = messagingTemplate;
        this.activeUsers = activeUsers;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        Principal user = event.getUser();
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        if (user != null && sessionId != null) {
            activeUsers.add(sessionId, user.getName());
            logger.info("WebSocket session connected for user {}", user.getName());
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        activeUsers.remove(event.getSessionId()).ifPresent(username -> {
            logger.info("User disconnected: {}", username);
            // Only announce LEAVE when the user's last open session (e.g. browser tab) closes.
            if (!activeUsers.isOnline(username)) {
                messagingTemplate.convertAndSend("/topic/public",
                        ChatMessage.of(ChatMessage.MessageType.LEAVE, username, null));
            }
        });
    }

}
