package com.example.kairo.platform.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * V1.7 M1-A &sect;8.1 test fixture: a {@link Clock} the lease/epoch tests advance deterministically
 * instead of sleeping. Thread-safe (an {@link AtomicReference} holds the current instant) so two
 * racing pollers released by a barrier read a consistent epoch; the test advances it between steps
 * to expire leases. This is the injectable {@code Clock} seam the plan requires (&sect;8.1: all time
 * is controlled by an injectable Clock; concurrency tests use barrier/latch, never long sleeps).
 */
final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    /** Advance the clock by {@code duration} (the only way tests "wait out" a lease). */
    void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }
}
