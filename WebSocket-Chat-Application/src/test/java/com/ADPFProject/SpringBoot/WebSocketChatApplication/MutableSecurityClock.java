package com.ADPFProject.SpringBoot.WebSocketChatApplication;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableSecurityClock extends Clock {

    private final AtomicReference<Instant> current;

    public MutableSecurityClock(Instant instant) {
        current = new AtomicReference<>(instant);
    }

    public void set(Instant instant) {
        current.set(instant);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
