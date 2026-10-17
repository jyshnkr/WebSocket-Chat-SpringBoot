package com.ADPFProject.SpringBoot.WebSocketChatApplication.config;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.security.JwtChannelInterceptor;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.security.SessionDeadlineInterceptor;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.security.WebSocketSessionLifetime;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
//Indicates that it is a Spring Configuration Class

@EnableWebSocketMessageBroker
//Enables WebSocket message handling, backed by a message broker
//In other words, it enables our webSocket Server

public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
//We implement WebSocketMessageBrokerConfigurer interface and provide implementation for some of its methods

    private final JwtChannelInterceptor jwtChannelInterceptor;
    private final SessionDeadlineInterceptor deadlines;
    private final WebSocketSessionLifetime lifetime;

    public WebSocketConfig(JwtChannelInterceptor jwtChannelInterceptor, SessionDeadlineInterceptor deadlines,
                           WebSocketSessionLifetime lifetime) {
        this.jwtChannelInterceptor = jwtChannelInterceptor;
        this.deadlines = deadlines;
        this.lifetime = lifetime;
    }

    @Override
    //We register a webSocket endpoint that clients will use to connect to our webSocket server
    //STOMP in the method name refers to Spring Framework STOMP implementation
    //STOMP - Simple Text Oriented Messaging Protocol
    public void registerStompEndpoints(StompEndpointRegistry registry) {

        //withSockJS() - used to enable fallback options for browser that don't support webSocket
        registry.addEndpoint("/ws").withSockJS();

        //Send short, non-revealing ERROR frames when a frame is rejected (e.g. missing or invalid token)
        registry.setErrorHandler(new StompErrorHandler());
    }


    @Override
    //We configured Message Broker in this method that will be used to route messages from one client to another
    public void configureMessageBroker(MessageBrokerRegistry registry) {

        //Messages whose destination starts with "/app" should be routed to message-handling methods
        registry.setApplicationDestinationPrefixes("/app");

        //Messages whose destination starts with "/topic" should be routed to message broker
        registry.enableSimpleBroker("/topic");
        registry.setPreservePublishOrder(true);
    }

    @Override
    //Every inbound STOMP frame passes through the JWT interceptor (authentication + destination checks)
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtChannelInterceptor, deadlines);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(deadlines);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(lifetime::decorate);
    }
}
