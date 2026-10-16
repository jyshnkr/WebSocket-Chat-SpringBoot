package com.ADPFProject.SpringBoot.WebSocketChatApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.model.ChatMessage;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.model.ChatMessage.MessageType;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.security.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * End-to-end checks of the STOMP security rules against a running server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketSecurityTests {

    private static final long TIMEOUT_SECONDS = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TestRestTemplate rest;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        // Strings for ERROR frames, JSON for chat messages
        stompClient.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new MappingJackson2MessageConverter())));
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    @Test
    void connectWithoutTokenIsRejected() throws Exception {
        RecordingSessionHandler handler = new RecordingSessionHandler();

        stompClient.connectAsync(url(), new WebSocketHttpHeaders(), new StompHeaders(), handler);

        assertThat(handler.errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("Unauthorized");
        assertThat(handler.connected.get()).isFalse();
    }

    @Test
    void connectWithInvalidTokenIsRejected() throws Exception {
        RecordingSessionHandler handler = new RecordingSessionHandler();

        stompClient.connectAsync(url(), new WebSocketHttpHeaders(), bearer("not-a-real-token"), handler);

        assertThat(handler.errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("Unauthorized");
        assertThat(handler.connected.get()).isFalse();
    }

    @Test
    void serverStampsAuthenticatedSenderOnMessages() throws Exception {
        String username = "alice-ws";
        StompSession session = connect(username, "ROLE_USER");
        BlockingQueue<ChatMessage> received = subscribeToPublicTopic(session, username);

        // A client trying to speak as someone else: the server ignores the supplied sender.
        session.send("/app/chat.sendMessage", ChatMessage.of(MessageType.CHAT, "mallory", "hello from alice"));

        ChatMessage message = awaitChat(received, "hello from alice");
        assertThat(message).isNotNull();
        assertThat(message.getSender()).isEqualTo(username);
        session.disconnect();
    }

    @Test
    void sendingStraightToTheBrokerTopicIsForbidden() throws Exception {
        RecordingSessionHandler handler = new RecordingSessionHandler();
        StompSession session = stompClient
                .connectAsync(url(), new WebSocketHttpHeaders(), bearer(token("bob-ws", "ROLE_USER")), handler)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        BlockingQueue<ChatMessage> received = subscribeToPublicTopic(session, "bob-ws");

        // Bypassing /app would skip the controller and let a client forge the sender.
        session.send("/topic/public", ChatMessage.of(MessageType.CHAT, "mallory", "forged message"));

        assertThat(handler.errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("Forbidden");
        assertThat(awaitChat(received, "forged message")).isNull();
    }

    @Test
    void adminCanSeeConnectedUsers() throws Exception {
        StompSession session = connect("carol-ws", "ROLE_USER");
        subscribeToPublicTopic(session, "carol-ws");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token("ops-admin", "ROLE_ADMIN", "ROLE_USER"));
        ResponseEntity<String[]> response = rest.exchange(
                "/api/admin/active-users", HttpMethod.GET, new HttpEntity<>(headers), String[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("carol-ws");
        session.disconnect();
    }

    private StompSession connect(String username, String... authorities) throws Exception {
        return stompClient
                .connectAsync(url(), new WebSocketHttpHeaders(), bearer(token(username, authorities)),
                        new RecordingSessionHandler())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Subscribes and waits until our own JOIN echo arrives, proving the subscription is live. */
    private BlockingQueue<ChatMessage> subscribeToPublicTopic(StompSession session, String username)
            throws InterruptedException {
        BlockingQueue<ChatMessage> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/public", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return ChatMessage.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.offer((ChatMessage) payload);
            }
        });
        for (int attempt = 0; attempt < 10; attempt++) {
            session.send("/app/chat.addUser", Map.of());
            ChatMessage message;
            while ((message = received.poll(500, TimeUnit.MILLISECONDS)) != null) {
                if (message.getType() == MessageType.JOIN && username.equals(message.getSender())) {
                    return received;
                }
            }
        }
        return fail("Subscription to /topic/public never became active");
    }

    private static ChatMessage awaitChat(BlockingQueue<ChatMessage> received, String content)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            ChatMessage message = received.poll(200, TimeUnit.MILLISECONDS);
            if (message != null && message.getType() == MessageType.CHAT && content.equals(message.getContent())) {
                return message;
            }
        }
        return null;
    }

    private String token(String username, String... authorities) {
        return tokenService.issue(UsernamePasswordAuthenticationToken.authenticated(
                username, null, AuthorityUtils.createAuthorityList(authorities))).value();
    }

    private static StompHeaders bearer(String token) {
        StompHeaders headers = new StompHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return headers;
    }

    private String url() {
        // SockJS endpoints also accept raw WebSocket connections at /{endpoint}/websocket
        return "ws://localhost:" + port + "/ws/websocket";
    }

    /** Records ERROR frames (as their "message" header) and transport failures. */
    private static class RecordingSessionHandler extends StompSessionHandlerAdapter {

        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final AtomicBoolean connected = new AtomicBoolean();

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            connected.set(true);
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.offer(String.valueOf(headers.getFirst("message")));
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errors.offer("transport-error: " + exception.getClass().getSimpleName());
        }
    }
}
