package com.ADPFProject.SpringBoot.WebSocketChatApplication.model;

public class ChatMessage {
    private MessageType type;
    private String content;
    private String sender;

    public enum MessageType{
        CHAT,
        JOIN,
        LEAVE
    }

    public static ChatMessage of(MessageType type, String sender, String content) {
        ChatMessage message = new ChatMessage();
        message.setType(type);
        message.setSender(sender);
        message.setContent(content);
        return message;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

}
