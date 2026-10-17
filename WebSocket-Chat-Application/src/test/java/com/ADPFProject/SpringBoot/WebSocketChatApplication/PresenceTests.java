package com.ADPFProject.SpringBoot.WebSocketChatApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.ADPFProject.SpringBoot.WebSocketChatApplication.controller.UserController;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.model.ChatMessage;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.model.ChatMessage.MessageType;
import com.ADPFProject.SpringBoot.WebSocketChatApplication.presence.ActiveUserRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

class PresenceTests {

    private final SimpMessageSendingOperations publisher = mock(SimpMessageSendingOperations.class);
    private final ActiveUserRegistry presence = new ActiveUserRegistry(publisher);
    private final UserController users = new UserController(presence);
    private final Map<String, AtomicBoolean> liveSessions = new ConcurrentHashMap<>();
    private final List<ChatMessage> publications = new CopyOnWriteArrayList<>();

    @BeforeEach
    void recordPublications() {
        doAnswer(invocation -> {
            publications.add(invocation.getArgument(1));
            return null;
        }).when(publisher).convertAndSend(eq("/topic/public"), any(ChatMessage.class));
    }

    @Test
    void everyExplicitAddUserPublishesJoin() {
        connected("alice-session", "alice");

        join("alice-session");
        join("alice-session");

        assertThat(publications).extracting(ChatMessage::getType)
                .containsExactly(MessageType.JOIN, MessageType.JOIN);
        assertThat(publications).extracting(ChatMessage::getSender).containsExactly("alice", "alice");
        assertThat(users.activeUsers()).containsExactly("alice");
    }

    @Test
    void delayedAddUserAfterDisconnectDoesNotPublishJoin() {
        connected("alice-session", "alice");
        disconnected("alice-session");

        join("alice-session");

        assertThat(publications).extracting(ChatMessage::getType).containsExactly(MessageType.LEAVE);
        assertThat(users.activeUsers()).isEmpty();
    }

    @Test
    void lastDisconnectEnqueuesLeaveBeforeReconnectAndJoin() throws Exception {
        connected("original-session", "alice");
        CountDownLatch leaveStarted = new CountDownLatch(1);
        CountDownLatch releaseLeave = new CountDownLatch(1);
        CountDownLatch reconnectStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(1);
            if (message.getType() == MessageType.LEAVE) {
                leaveStarted.countDown();
                assertThat(releaseLeave.await(5, TimeUnit.SECONDS)).isTrue();
            }
            publications.add(message);
            return null;
        }).when(publisher).convertAndSend(eq("/topic/public"), any(ChatMessage.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> disconnect = executor.submit(() -> disconnected("original-session"));
            assertThat(leaveStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> reconnect = executor.submit(() -> {
                reconnectStarted.countDown();
                connected("replacement-session", "alice");
                join("replacement-session");
            });
            assertThat(reconnectStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> reconnect.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(publications).isEmpty();
            releaseLeave.countDown();
            disconnect.get(5, TimeUnit.SECONDS);
            reconnect.get(5, TimeUnit.SECONDS);

            assertThat(publications).extracting(ChatMessage::getType)
                    .containsExactly(MessageType.LEAVE, MessageType.JOIN);
            assertThat(publications).extracting(ChatMessage::getSender).containsExactly("alice", "alice");
            assertThat(users.activeUsers()).containsExactly("alice");
        } finally {
            releaseLeave.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void onlyTheLastOfTwoSessionsPublishesLeave() {
        connected("first-session", "alice");
        connected("second-session", "alice");
        assertThat(users.activeUsers()).containsExactly("alice");

        disconnected("first-session");

        assertThat(users.activeUsers()).containsExactly("alice");
        assertThat(publications).isEmpty();

        disconnected("second-session");

        assertThat(users.activeUsers()).isEmpty();
        assertThat(publications).extracting(ChatMessage::getType).containsExactly(MessageType.LEAVE);
        assertThat(publications).extracting(ChatMessage::getSender).containsExactly("alice");
    }

    @Test
    void simultaneousLastDisconnectsPublishOneLeave() throws Exception {
        connected("first-session", "alice");
        connected("second-session", "alice");
        CyclicBarrier startTogether = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstClose = executor.submit(() -> {
                startTogether.await(5, TimeUnit.SECONDS);
                disconnected("first-session");
                return null;
            });
            Future<?> secondClose = executor.submit(() -> {
                startTogether.await(5, TimeUnit.SECONDS);
                disconnected("second-session");
                return null;
            });
            startTogether.await(5, TimeUnit.SECONDS);
            firstClose.get(5, TimeUnit.SECONDS);
            secondClose.get(5, TimeUnit.SECONDS);

            assertThat(users.activeUsers()).isEmpty();
            assertThat(publications).extracting(ChatMessage::getType).containsExactly(MessageType.LEAVE);
            assertThat(publications).extracting(ChatMessage::getSender).containsExactly("alice");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void duplicateDisconnectsDoNotPublishAnotherLeave() {
        connected("alice-session", "alice");

        disconnected("alice-session");
        disconnected("alice-session");
        disconnected("unknown-session");

        assertThat(users.activeUsers()).isEmpty();
        assertThat(publications).extracting(ChatMessage::getType).containsExactly(MessageType.LEAVE);
    }

    @Test
    void reconnectBeforeTheLastRemovalSuppressesLeave() throws Exception {
        connected("original-session", "alice");
        CountDownLatch replacementRegistered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> reconnect = executor.submit(() -> {
                connected("replacement-session", "alice");
                join("replacement-session");
                replacementRegistered.countDown();
            });
            Future<?> disconnect = executor.submit(() -> {
                assertThat(replacementRegistered.await(5, TimeUnit.SECONDS)).isTrue();
                disconnected("original-session");
                return null;
            });
            reconnect.get(5, TimeUnit.SECONDS);
            disconnect.get(5, TimeUnit.SECONDS);

            assertThat(users.activeUsers()).containsExactly("alice");
            assertThat(publications).extracting(ChatMessage::getType).containsExactly(MessageType.JOIN);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void inactiveSessionCannotRegisterOrEmitLeave() {
        presence.connected("closed-session", "alice", () -> false);
        disconnected("closed-session");

        assertThat(users.activeUsers()).isEmpty();
        assertThat(publications).isEmpty();
    }

    @Test
    void disconnectCannotOvertakeTheActiveCheckAndLeaveARegisteredSession() throws Exception {
        CountDownLatch activeCheckStarted = new CountDownLatch(1);
        CountDownLatch releaseActiveCheck = new CountDownLatch(1);
        CountDownLatch disconnectStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> connect = executor.submit(() -> presence.connected("alice-session", "alice", () -> {
                activeCheckStarted.countDown();
                try {
                    assertThat(releaseActiveCheck.await(5, TimeUnit.SECONDS)).isTrue();
                    return true;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }));
            assertThat(activeCheckStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> disconnect = executor.submit(() -> {
                disconnectStarted.countDown();
                disconnected("alice-session");
            });
            assertThat(disconnectStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> disconnect.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseActiveCheck.countDown();
            connect.get(5, TimeUnit.SECONDS);
            disconnect.get(5, TimeUnit.SECONDS);

            assertThat(users.activeUsers()).isEmpty();
            assertThat(publications).extracting(ChatMessage::getType).containsExactly(MessageType.LEAVE);
        } finally {
            releaseActiveCheck.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void connected(String sessionId, String username) {
        AtomicBoolean active = new AtomicBoolean(true);
        liveSessions.put(sessionId, active);
        presence.connected(sessionId, username, active::get);
    }

    private void disconnected(String sessionId) {
        AtomicBoolean active = liveSessions.get(sessionId);
        if (active != null) {
            active.set(false);
        }
        presence.disconnected(sessionId);
    }

    private void join(String sessionId) {
        presence.join(sessionId, "alice", () -> {
            AtomicBoolean active = liveSessions.get(sessionId);
            return active != null && active.get();
        });
    }

    @Test
    void anExpiredSessionCannotPublishJoinBeforeItsDisconnectEventArrives() {
        connected("alice-session", "alice");
        liveSessions.get("alice-session").set(false);

        join("alice-session");

        assertThat(publications).isEmpty();
    }

    @Test
    void aDelayedJoinCannotBorrowAReplacementSessionsPresence() {
        connected("original-session", "alice");
        disconnected("original-session");
        connected("replacement-session", "alice");

        join("original-session");

        assertThat(publications).extracting(ChatMessage::getType).containsExactly(MessageType.LEAVE);
        assertThat(users.activeUsers()).containsExactly("alice");
    }

    @Test
    void liveJoinRegistersPresenceEvenIfTheConnectedEventHasNotRunYet() {
        presence.join("alice-session", "alice", () -> true);

        assertThat(publications).extracting(ChatMessage::getType).containsExactly(MessageType.JOIN);
        assertThat(users.activeUsers()).containsExactly("alice");
    }
}
