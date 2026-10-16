package com.ADPFProject.SpringBoot.WebSocketChatApplication.presence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Tracks which authenticated users currently hold an open WebSocket session.
 */
@Component
public class ActiveUserRegistry {

    private final Map<String, String> usernamesBySessionId = new ConcurrentHashMap<>();

    public void add(String sessionId, String username) {
        usernamesBySessionId.put(sessionId, username);
    }

    public Optional<String> remove(String sessionId) {
        return Optional.ofNullable(usernamesBySessionId.remove(sessionId));
    }

    public boolean isOnline(String username) {
        return usernamesBySessionId.containsValue(username);
    }

    public List<String> usernames() {
        return usernamesBySessionId.values().stream().distinct().sorted().toList();
    }
}
