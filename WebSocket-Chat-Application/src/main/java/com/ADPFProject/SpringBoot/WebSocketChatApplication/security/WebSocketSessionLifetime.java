package com.ADPFProject.SpringBoot.WebSocketChatApplication.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

@Component
public class WebSocketSessionLifetime {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketSessionLifetime.class);
    private static final CloseStatus EXPIRED = new CloseStatus(1008, "Session expired");
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final JwtLifetimePolicy policy;
    private boolean stopping;

    public WebSocketSessionLifetime(Clock clock, @Qualifier("sessionExpiryScheduler") TaskScheduler scheduler,
                                    JwtLifetimePolicy policy) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.policy = policy;
    }

    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                if (!opened(session)) {
                    session.close(CloseStatus.GOING_AWAY);
                    return;
                }
                try {
                    super.afterConnectionEstablished(new WebSocketSessionDecorator(session) {
                        @Override
                        public void sendMessage(WebSocketMessage<?> message) throws IOException {
                            if (transportAllowed(getId())) {
                                super.sendMessage(message);
                            }
                        }
                    });
                } catch (Exception exception) {
                    Session failed = sessions.get(session.getId());
                    if (failed != null) {
                        terminate(failed, CloseStatus.SERVER_ERROR);
                    }
                    throw exception;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                transportClosed(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    private synchronized boolean opened(WebSocketSession session) {
        return !stopping && sessions.putIfAbsent(session.getId(), new Session(session)) == null;
    }

    public void authenticate(String sessionId, Jwt token) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            throw new BadCredentialsException("Session is closed");
        }
        Instant deadline = policy.deadline(token);
        synchronized (session) {
            if (session.closed || session.deadline != null || !clock.instant().isBefore(deadline)) {
                throw new BadCredentialsException("Session is not available");
            }
            session.deadline = deadline;
        }
        try {
            ScheduledFuture<?> expiry = scheduler.schedule(() -> expire(session), deadline);
            if (expiry == null) {
                throw new IllegalStateException("Expiry task was not scheduled");
            }
            synchronized (session) {
                if (session.closed) {
                    expiry.cancel(false);
                    throw new BadCredentialsException("Session is closed");
                }
                session.expiry = expiry;
            }
        } catch (RuntimeException exception) {
            terminate(session, CloseStatus.SERVER_ERROR);
            throw new BadCredentialsException("Session expiry could not be scheduled", exception);
        }
    }

    public boolean isActive(String sessionId) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            return !session.closed && session.deadline != null && clock.instant().isBefore(session.deadline);
        }
    }

    public boolean allowMessage(String sessionId) {
        if (isActive(sessionId)) {
            return true;
        }
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session != null) {
            expireIfDue(session);
        }
        return false;
    }

    private boolean transportAllowed(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        return transportAllowed(session);
    }

    public BooleanSupplier httpTransportGuard(String sessionId) {
        AtomicReference<Session> observed = new AtomicReference<>(sessions.get(sessionId));
        return () -> {
            Session session = observed.get();
            if (session == null) {
                session = sessions.get(sessionId);
                if (session == null) {
                    return true;
                }
                observed.compareAndSet(null, session);
                session = observed.get();
            }
            return transportAllowed(session);
        };
    }

    private boolean transportAllowed(Session session) {
        synchronized (session) {
            if (session.closed) {
                return false;
            }
            if (session.deadline == null || clock.instant().isBefore(session.deadline)) {
                return true;
            }
        }
        expire(session);
        return false;
    }

    private void expireIfDue(Session session) {
        synchronized (session) {
            if (session.closed || session.deadline == null || clock.instant().isBefore(session.deadline)) {
                return;
            }
        }
        expire(session);
    }

    private void expire(Session session) {
        terminate(session, EXPIRED);
    }

    public void disconnected(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            markClosed(session);
        }
    }

    private void transportClosed(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session != null) {
            markClosed(session);
        }
    }

    private boolean markClosed(Session session) {
        synchronized (session) {
            if (session.closed) {
                return false;
            }
            session.closed = true;
            if (session.expiry != null) {
                session.expiry.cancel(false);
            }
            return true;
        }
    }

    private void terminate(Session session, CloseStatus status) {
        if (markClosed(session)) {
            try {
                session.transport.close(status);
            } catch (IOException exception) {
                logger.warn("Could not close WebSocket session {}", session.transport.getId(), exception);
            } finally {
                if (!session.transport.isOpen()) {
                    sessions.remove(session.transport.getId(), session);
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (this) {
            stopping = true;
        }
        sessions.values().forEach(session -> terminate(session, CloseStatus.GOING_AWAY));
    }

    private static final class Session {
        private final WebSocketSession transport;
        private Instant deadline;
        private ScheduledFuture<?> expiry;
        private boolean closed;

        private Session(WebSocketSession transport) {
            this.transport = transport;
        }
    }
}
