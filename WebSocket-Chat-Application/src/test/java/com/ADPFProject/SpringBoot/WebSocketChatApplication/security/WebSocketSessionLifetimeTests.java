package com.ADPFProject.SpringBoot.WebSocketChatApplication.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.MutableSecurityClock;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.controller.WebSocketEventListener;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.presence.ActiveUserRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

class WebSocketSessionLifetimeTests {

    private static final Instant EXPIRY = Instant.parse("2026-10-16T12:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-10-16T12:01:00Z");
    private final MutableSecurityClock clock = new MutableSecurityClock(EXPIRY);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    private final WebSocketSession transport = mock(WebSocketSession.class);
    private final List<CloseStatus> closes = new ArrayList<>();
    private final List<WebSocketMessage<?>> delivered = new ArrayList<>();
    private final AtomicReference<Runnable> timer = new AtomicReference<>();
    private final WebSocketSessionLifetime lifetime = new WebSocketSessionLifetime(
            clock, scheduler, new JwtLifetimePolicy(clock));
    private final SessionDeadlineInterceptor interceptor = new SessionDeadlineInterceptor(lifetime);
    private WebSocketSession outbound;
    private WebSocketHandler handler;

    @BeforeEach
    void openTransport() throws Exception {
        when(transport.getId()).thenReturn("session-1");
        when(transport.isOpen()).thenAnswer(invocation -> closes.isEmpty());
        doAnswer(invocation -> {
            closes.add(invocation.getArgument(0));
            return null;
        }).when(transport).close(any(CloseStatus.class));
        doAnswer(invocation -> {
            delivered.add(invocation.getArgument(0));
            return null;
        }).when(transport).sendMessage(any(WebSocketMessage.class));
        doAnswer(invocation -> {
            assertThat((Instant) invocation.getArgument(1)).isEqualTo(DEADLINE);
            timer.set(invocation.getArgument(0));
            return future;
        }).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        handler = lifetime.decorate(new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) {
                outbound = session;
            }
        });
        handler.afterConnectionEstablished(transport);
    }

    @Test
    void deadlineRejectsNewAndQueuedWorkEvenWhenTheTimerIsLate() throws Exception {
        lifetime.authenticate("session-1", token());
        Message<?> message = frame(SimpMessageType.MESSAGE);
        clock.set(DEADLINE.minusNanos(1));
        assertThat(interceptor.preSend(message, null)).isSameAs(message);
        assertThat(interceptor.beforeHandle(message, null, null)).isSameAs(message);
        outbound.sendMessage(new TextMessage("before"));

        clock.set(DEADLINE);

        assertThat(interceptor.beforeHandle(message, null, null)).isNull();
        assertThat(interceptor.preSend(frame(SimpMessageType.SUBSCRIBE), null)).isNull();
        outbound.sendMessage(new TextMessage("after"));
        assertThat(delivered).singleElement().satisfies(messageSent ->
                assertThat(messageSent.getPayload()).isEqualTo("before"));
        assertThat(closes).containsExactly(new CloseStatus(1008, "Session expired"));
        assertThat(lifetime.isActive("session-1")).isFalse();
        assertThat(interceptor.beforeHandle(frame(SimpMessageType.DISCONNECT), null, null)).isNotNull();
    }

    @Test
    void finalTransportSendChecksTheDeadlineAfterChannelAdmission() throws Exception {
        lifetime.authenticate("session-1", token());
        assertThat(interceptor.beforeHandle(frame(SimpMessageType.MESSAGE), null, null)).isNotNull();
        clock.set(DEADLINE);

        outbound.sendMessage(new TextMessage("queued"));

        assertThat(delivered).isEmpty();
        assertThat(closes).containsExactly(new CloseStatus(1008, "Session expired"));
    }

    @Test
    void httpWriteChecksDeadlineAfterRequestAdmission() {
        lifetime.authenticate("session-1", token());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws/000/session-1/xhr");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SockJsDeadlineFilter filter = new SockJsDeadlineFilter(lifetime);

        assertThatThrownBy(() -> filter.doFilter(request, response, (incoming, outgoing) -> {
            clock.set(DEADLINE);
            outgoing.getOutputStream().write("cached".getBytes(StandardCharsets.UTF_8));
        })).isInstanceOf(IOException.class);

        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(closes).containsExactly(new CloseStatus(1008, "Session expired"));
    }

    @Test
    void httpWriteGuardRemembersClosureAfterTheRegistryEntryIsRemoved() throws Exception {
        lifetime.authenticate("session-1", token());
        var allowed = lifetime.httpTransportGuard("session-1");
        assertThat(allowed.getAsBoolean()).isTrue();

        transport.close(CloseStatus.NORMAL);
        handler.afterConnectionClosed(transport, CloseStatus.NORMAL);

        assertThat(allowed.getAsBoolean()).isFalse();
    }

    @Test
    void aFreshHttpPollIsDeniedWhilePhysicalCloseIsStillInProgress() throws Exception {
        lifetime.authenticate("session-1", token());
        doAnswer(invocation -> {
            assertThat(lifetime.httpTransportGuard("session-1").getAsBoolean()).isFalse();
            closes.add(invocation.getArgument(0));
            return null;
        }).when(transport).close(any(CloseStatus.class));
        clock.set(DEADLINE);

        timer.get().run();

        assertThat(closes).containsExactly(new CloseStatus(1008, "Session expired"));
    }

    @Test
    void failedPhysicalCloseDoesNotMakeANewHttpGuardPermissive() throws Exception {
        lifetime.authenticate("session-1", token());
        doThrow(new IOException("transport close failed")).when(transport).close(any(CloseStatus.class));
        clock.set(DEADLINE);

        timer.get().run();

        assertThat(lifetime.httpTransportGuard("session-1").getAsBoolean()).isFalse();
    }

    @Test
    void protocolDisconnectCannotReopenHttpDeliveryBeforePhysicalClosure() {
        lifetime.authenticate("session-1", token());
        ActiveUserRegistry presence = new ActiveUserRegistry(new SimpMessagingTemplate((message, timeout) -> true));
        WebSocketEventListener listener = new WebSocketEventListener(presence, lifetime);

        listener.handleWebSocketDisconnectListener(new SessionDisconnectEvent(this,
                frame(SimpMessageType.DISCONNECT), "session-1", CloseStatus.SESSION_NOT_RELIABLE));
        clock.set(DEADLINE);

        assertThat(transport.isOpen()).isTrue();
        assertThat(lifetime.isActive("session-1")).isFalse();
        assertThat(lifetime.httpTransportGuard("session-1").getAsBoolean()).isFalse();
    }

    @Test
    void scheduledExpiryClosesAnIdleSessionOnce() {
        lifetime.authenticate("session-1", token());
        clock.set(DEADLINE);

        timer.get().run();
        timer.get().run();
        lifetime.disconnected("session-1");

        assertThat(closes).containsExactly(new CloseStatus(1008, "Session expired"));
        assertThat(lifetime.isActive("session-1")).isFalse();
        org.mockito.Mockito.verify(future).cancel(false);
    }

    @Test
    void disconnectCancelsExpiryAndCannotBeResurrectedByLateAuthentication() throws Exception {
        lifetime.authenticate("session-1", token());

        handler.afterConnectionClosed(transport, CloseStatus.NORMAL);
        handler.afterConnectionClosed(transport, CloseStatus.NORMAL);
        timer.get().run();

        assertThat(lifetime.isActive("session-1")).isFalse();
        assertThatThrownBy(() -> lifetime.authenticate("session-1", token()))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(closes).isEmpty();
        org.mockito.Mockito.verify(future).cancel(false);
    }

    @Test
    void closeDuringTaskRegistrationCancelsTheReturnedTask() {
        doAnswer(invocation -> {
            lifetime.disconnected("session-1");
            return future;
        }).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        assertThatThrownBy(() -> lifetime.authenticate("session-1", token()))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(lifetime.isActive("session-1")).isFalse();
        org.mockito.Mockito.verify(future).cancel(false);
    }

    @Test
    void schedulingFailureClosesTheSessionInsteadOfLeavingItUnlimited() {
        doThrow(new RejectedExecutionException("stopped"))
                .when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        assertThatThrownBy(() -> lifetime.authenticate("session-1", token()))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(closes).containsExactly(CloseStatus.SERVER_ERROR);
        assertThat(lifetime.allowMessage("session-1")).isFalse();
    }

    @Test
    void shutdownClosesSessionsCancelsTasksAndRejectsNewTransports() throws Exception {
        lifetime.authenticate("session-1", token());

        lifetime.shutdown();
        lifetime.shutdown();
        handler.afterConnectionEstablished(transport);

        assertThat(lifetime.isActive("session-1")).isFalse();
        assertThat(closes).containsExactly(CloseStatus.GOING_AWAY, CloseStatus.GOING_AWAY);
        org.mockito.Mockito.verify(future).cancel(false);
    }

    private Jwt token() {
        return Jwt.withTokenValue("test-token").header("alg", "HS256").subject("alice")
                .issuedAt(EXPIRY.minusSeconds(120)).expiresAt(EXPIRY).build();
    }

    private Message<byte[]> frame(SimpMessageType type) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(type);
        headers.setSessionId("session-1");
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
