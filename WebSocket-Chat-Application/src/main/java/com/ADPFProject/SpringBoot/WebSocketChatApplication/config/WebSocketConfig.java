package com.ADPFProject.SpringBoot.WebSocketChatApplication.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
//Indicates that it is a Spring Configuration Class

@EnableWebSocketMessageBroker
//Enables WebSocket message handling, backed by a message broker
//In other words, it enables our webSocket Server

public class WebSocketConfig implements WebSocketMessageBrokerConfigurer{
//We implement WebSocketMessageBrokerConfigurer interface and provide implementation for some of its methods

    @Override
    //We register a webSocket endpoint that clients will use to connect to our websocket server
    //STOMP in the method name refers to Spring Framework STOMP implementation
    //STOMP - Simple Text Oriented Messaging Protocol
    public void registerStompEndpoints(StompEndpointRegistry registry){

        //withSockJS() - used to enable fallback options for browser that don't support webscket
        registry.addEndpoint("/ws").withSockJS();
    }

    @Override
    //We configured Message Broker in this method that will be used to route messages from one client to another
    public void configureMessageBroker(MessageBrokerRegistry registry) {

        //Messages whose destination starts with "/app" should be routed to message-handling methods
        registry.setApplicationDestinationPrefixes("/app");

        //Messages whose destination starts with "/topic" should be routed to message broker
        registry.enableSimpleBroker("/topic");
    }
}
