package com.ADPFProject.SpringBoot.WebSocketChatApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.model.ChatMessage;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.model.ChatMessage.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SessionExpiryTransportTests.TimeConfiguration.class)
@DirtiesContext
class SessionExpiryTransportTests {

    private static final Instant START = Instant.parse("2026-10-16T12:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-10-16T12:02:00Z");

    @LocalServerPort
    private int port;

    @Autowired
    private MutableSecurityClock clock;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CacheDeliveryProbe cacheDelivery;

    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(strings = {"xhr", "xhr/"})
    void cachedHttpPollingMessageIsNotFlushedAfterDeadlineWhenTimerIsLate(String receivePath) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String sessionId = "paused-" + UUID.randomUUID();
        String endpoint = "http://localhost:" + port + "/ws/000/" + sessionId + "/";
        assertThat(http.send(request(endpoint + "xhr", ""), HttpResponse.BodyHandlers.ofString()).body())
                .isEqualTo("o\n");
        String token = encode("paused-poller", START.plusSeconds(60));
        sendFrames(http, endpoint, "CONNECT\naccept-version:1.2\nAuthorization:Bearer " + token + "\n\n\0");
        assertThat(http.send(request(endpoint + "xhr", ""), HttpResponse.BodyHandlers.ofString()).body())
                .contains("CONNECTED");
        sendFrames(http, endpoint, "SUBSCRIBE\nid:paused\ndestination:/topic/public\n\n\0");
        var ready = http.sendAsync(request(endpoint + "xhr", ""), HttpResponse.BodyHandlers.ofString());
        for (int attempt = 0; attempt < 10 && !ready.isDone(); attempt++) {
            sendFrames(http, endpoint,
                    "SEND\ndestination:/app/chat.addUser\ncontent-type:application/json\n\n{}\0");
            try {
                ready.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                assertThat(attempt).isLessThan(9);
            }
        }
        assertThat(ready.get(5, TimeUnit.SECONDS).body()).contains("JOIN");
        Peer healthy = connect("cache-publisher", START.plusSeconds(3600), false);
        cacheDelivery.sessionId = sessionId;
        cacheDelivery.queued = new CountDownLatch(1);
        healthy.session.send("/app/chat.sendMessage",
                ChatMessage.of(MessageType.CHAT, "forged", "cached-before-deadline"));
        assertThat(cacheDelivery.queued.await(5, TimeUnit.SECONDS)).isTrue();
        clock.set(DEADLINE);

        HttpResponse<String> response = http.send(request(endpoint + receivePath, ""),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).doesNotContain("cached-before-deadline");
        assertThat(response.statusCode() == 410 || response.body().contains("c[")).isTrue();
    }

    @BeforeEach
    void resetTime() {
        clock.set(START);
    }

    @AfterEach
    void closeClients() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        clients.forEach(WebSocketStompClient::stop);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredSubscriberReceivesNoNewChatWhileHealthyPeerContinues(boolean httpFallback) throws Exception {
        Peer expiring = connect("expiring-reader", START.plusSeconds(60), httpFallback);
        Peer healthy = connect("healthy-reader", START.plusSeconds(3600), httpFallback);
        subscribe(expiring);
        subscribe(healthy);
        clock.set(DEADLINE);

        healthy.session.send("/app/chat.sendMessage", ChatMessage.of(MessageType.CHAT, "forged", "after-deadline"));

        assertThat(awaitChat(healthy, "after-deadline").getSender()).isEqualTo("healthy-reader");
        assertThat(expiring.closed.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(expiring.messages).noneMatch(message -> "after-deadline".equals(message.getContent()));
        assertThat(healthy.session.isConnected()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredSenderCannotPublishButHealthyPeerCan(boolean httpFallback) throws Exception {
        Peer expiring = connect("expiring-sender", START.plusSeconds(60), httpFallback);
        Peer healthy = connect("healthy-sender", START.plusSeconds(3600), httpFallback);
        subscribe(expiring);
        subscribe(healthy);
        clock.set(DEADLINE);

        assertExpiredFrameRejected(httpFallback, () -> expiring.session.send("/app/chat.sendMessage",
                ChatMessage.of(MessageType.CHAT, "forged", "expired-send")));

        assertThat(expiring.closed.poll(5, TimeUnit.SECONDS)).isNotNull();
        healthy.session.send("/app/chat.sendMessage", ChatMessage.of(MessageType.CHAT, "forged", "healthy-send"));
        assertThat(awaitChat(healthy, "healthy-send").getSender()).isEqualTo("healthy-sender");
        assertThat(healthy.messages).noneMatch(message -> "expired-send".equals(message.getContent()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredSessionCannotAddASubscription(boolean httpFallback) throws Exception {
        Peer expiring = connect("expiring-subscription", START.plusSeconds(60), httpFallback);
        clock.set(DEADLINE);

        assertExpiredFrameRejected(httpFallback, () -> expiring.session.subscribe("/topic/public", expiring));

        assertThat(expiring.closed.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(expiring.session.isConnected()).isFalse();
    }

    private void assertExpiredFrameRejected(boolean httpFallback, Runnable send) {
        if (httpFallback) {
            assertThatThrownBy(send::run).isInstanceOf(MessageDeliveryException.class)
                    .hasCauseInstanceOf(HttpClientErrorException.Gone.class);
        } else {
            send.run();
        }
    }

    private Peer connect(String username, Instant expiry, boolean httpFallback) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(httpFallback
                ? new SockJsClient(List.of(new RestTemplateXhrTransport())) : new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new MappingJackson2MessageConverter())));
        clients.add(client);
        String token = encode(username, expiry);
        StompHeaders headers = new StompHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        Peer peer = new Peer(username);
        String endpoint = httpFallback ? "http://localhost:" + port + "/ws"
                : "ws://localhost:" + port + "/ws/websocket";
        peer.session = client.connectAsync(endpoint, new WebSocketHttpHeaders(), headers, peer)
                .get(5, TimeUnit.SECONDS);
        sessions.add(peer.session);
        return peer;
    }

    private String encode(String username, Instant expiry) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("websocket-chat").subject(username)
                .issuedAt(START.minusSeconds(60)).expiresAt(expiry).claim("roles", List.of("USER")).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private HttpRequest request(String endpoint, String body) {
        return HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private void sendFrames(HttpClient http, String endpoint, String... frames) throws Exception {
        assertThat(http.send(request(endpoint + "xhr_send", json.writeValueAsString(frames)),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
    }

    private void subscribe(Peer peer) throws Exception {
        peer.session.subscribe("/topic/public", peer);
        for (int attempt = 0; attempt < 10; attempt++) {
            peer.session.send("/app/chat.addUser", Map.of());
            ChatMessage message = peer.pending.poll(500, TimeUnit.MILLISECONDS);
            if (message != null && message.getType() == MessageType.JOIN
                    && peer.username.equals(message.getSender())) {
                return;
            }
        }
        fail("Subscription did not become ready for " + peer.username);
    }

    private ChatMessage awaitChat(Peer peer, String content) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            ChatMessage message = peer.pending.poll(100, TimeUnit.MILLISECONDS);
            if (message != null && content.equals(message.getContent())) {
                return message;
            }
        }
        return fail("Chat was not delivered: " + content);
    }

    private static class Peer extends StompSessionHandlerAdapter implements StompFrameHandler {
        private final String username;
        private final BlockingQueue<ChatMessage> messages = new LinkedBlockingQueue<>();
        private final BlockingQueue<ChatMessage> pending = new LinkedBlockingQueue<>();
        private final BlockingQueue<Throwable> closed = new LinkedBlockingQueue<>();
        private StompSession session;

        private Peer(String username) {
            this.username = username;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return ChatMessage.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            messages.add((ChatMessage) payload);
            pending.add((ChatMessage) payload);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            closed.add(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableSecurityClock testClock() {
            return new MutableSecurityClock(START);
        }

        @Bean
        CacheDeliveryProbe cacheDeliveryProbe() {
            return new CacheDeliveryProbe();
        }

        @Bean
        WebSocketMessageBrokerConfigurer cacheDeliveryConfigurer(CacheDeliveryProbe probe) {
            return new WebSocketMessageBrokerConfigurer() {
                @Override
                public void configureClientOutboundChannel(ChannelRegistration registration) {
                    registration.interceptors(probe);
                }
            };
        }
    }

    static class CacheDeliveryProbe implements ExecutorChannelInterceptor {
        private volatile String sessionId = "";
        private volatile CountDownLatch queued = new CountDownLatch(1);

        @Override
        public void afterMessageHandled(Message<?> message, MessageChannel channel,
                                        MessageHandler handler, Exception exception) {
            if (exception == null && sessionId.equals(
                    SimpMessageHeaderAccessor.getSessionId(message.getHeaders()))
                    && message.getPayload() instanceof byte[] payload
                    && new String(payload, StandardCharsets.UTF_8).contains("cached-before-deadline")) {
                queued.countDown();
            }
        }
    }
}
