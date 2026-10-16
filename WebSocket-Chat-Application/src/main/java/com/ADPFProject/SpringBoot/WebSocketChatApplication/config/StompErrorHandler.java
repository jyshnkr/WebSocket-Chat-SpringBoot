package com.ADPFProject.SpringBoot.WebSocketChatApplication.config;

import java.nio.charset.StandardCharsets;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * Turns failures on inbound frames into short STOMP ERROR frames without leaking internal details.
 */
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    static final String UNAUTHORIZED = "Unauthorized";
    static final String FORBIDDEN = "Forbidden";
    static final String GENERIC = "Unable to process message";

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof AuthenticationException) {
                return errorFrame(UNAUTHORIZED);
            }
            if (cause instanceof AccessDeniedException) {
                return errorFrame(FORBIDDEN);
            }
        }
        return errorFrame(GENERIC);
    }

    private static Message<byte[]> errorFrame(String text) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(text);
        accessor.setContentType(MimeTypeUtils.TEXT_PLAIN);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(text.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }
}
