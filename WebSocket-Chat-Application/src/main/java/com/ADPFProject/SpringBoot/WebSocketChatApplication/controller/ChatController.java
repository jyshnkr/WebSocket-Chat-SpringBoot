package com.ADPFProject.SpringBoot.WebSocketChatApplication.controller;

import java.security.Principal;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.model.ChatMessage;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class ChatController {

    static final int MAX_CONTENT_LENGTH = 1000;

    @MessageMapping("/chat.sendMessage")
    @SendTo("/topic/public")
    public ChatMessage sendMessage(@Payload ChatMessage chatMessage, Principal principal) {
        String content = chatMessage.getContent() == null ? "" : chatMessage.getContent().strip();
        if (content.isEmpty() || content.length() > MAX_CONTENT_LENGTH) {
            return null; // nothing is broadcast for empty or oversized messages
        }
        // The sender always comes from the authenticated principal; any client-supplied sender is ignored.
        return ChatMessage.of(ChatMessage.MessageType.CHAT, principal.getName(), content);
    }

    @MessageMapping("/chat.addUser")
    @SendTo("/topic/public")
    public ChatMessage addUser(Principal principal) {
        return ChatMessage.of(ChatMessage.MessageType.JOIN, principal.getName(), null);
    }

}
