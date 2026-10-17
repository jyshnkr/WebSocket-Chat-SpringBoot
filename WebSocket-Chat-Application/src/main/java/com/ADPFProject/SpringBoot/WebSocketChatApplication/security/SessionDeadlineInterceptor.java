package com.ADPFProject.SpringBoot.WebSocketChatApplication.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

@Component
public class SessionDeadlineInterceptor implements ExecutorChannelInterceptor {

    private final WebSocketSessionLifetime lifetime;

    public SessionDeadlineInterceptor(WebSocketSessionLifetime lifetime) {
        this.lifetime = lifetime;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        return check(message);
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        return check(message);
    }

    private Message<?> check(Message<?> message) {
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        if (type == SimpMessageType.DISCONNECT || type == SimpMessageType.DISCONNECT_ACK
                || type == SimpMessageType.OTHER) {
            return message;
        }
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        return lifetime.allowMessage(sessionId) ? message : null;
    }
}
