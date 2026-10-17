package com.ADPFProject.SpringBoot.WebSocketChatApplication.presence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.model.ChatMessage;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;

/**
 * Tracks which authenticated users currently hold an open WebSocket session.
 */
@Component
public class ActiveUserRegistry {

    private final Map<String, String> usernamesBySessionId = new HashMap<>();
    private final SimpMessageSendingOperations publisher;

    public ActiveUserRegistry(SimpMessageSendingOperations publisher) {
        this.publisher = publisher;
    }

    public synchronized void connected(String sessionId, String username, BooleanSupplier sessionIsActive) {
        if (sessionIsActive.getAsBoolean()) {
            usernamesBySessionId.put(sessionId, username);
        }
    }

    public synchronized void disconnected(String sessionId) {
        String username = usernamesBySessionId.remove(sessionId);
        if (username != null && !usernamesBySessionId.containsValue(username)) {
            publisher.convertAndSend("/topic/public", ChatMessage.of(ChatMessage.MessageType.LEAVE, username, null));
        }
    }

    public synchronized void join(String sessionId, String username, BooleanSupplier sessionIsActive) {
        if (sessionIsActive.getAsBoolean()) {
            usernamesBySessionId.putIfAbsent(sessionId, username);
            publisher.convertAndSend("/topic/public", ChatMessage.of(ChatMessage.MessageType.JOIN, username, null));
        }
    }

    public synchronized List<String> usernames() {
        return usernamesBySessionId.values().stream().distinct().sorted().toList();
    }
}
