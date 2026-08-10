package com.example.kairo.perf.soak;

import java.time.Duration;
import java.time.Instant;

/**
 * The time source the M2-D soak harness (&sect;9.4) drives its cadence from. The cadence
 * itself (1m summary / 5m batch / 30m disconnect) is fixed in {@link SoakCadence} and is
 * identical for every clock; only the rate at which wall time advances differs.
 *
 * <p><b>Production ({@link WallClock}).</b> {@link #now()} returns a wall timestamp while
 * {@link #elapsed()} uses monotonic {@link System#nanoTime()} and {@link #tick()} is a no-op.
 * Real elapsed time advances naturally as the loop runs real enhanced invocations, so NTP or
 * operator clock adjustments cannot shorten or extend the gate and the 1m/5m/30m cadence fires
 * at genuine elapsed-time intervals. This is the
 * real RC ({@code PT2H}) / RELEASE ({@code P7D}) behaviour - sustained real load, no shell
 * sleep, no time inflation.
 *
 * <p><b>Test-only ({@link AcceleratedClock}).</b> Advances a virtual instant by a fixed
 * {@code step} per {@link #tick()} so a full cadence sequence (summaries + batches + a
 * disconnect/recovery + final summary) completes in milliseconds of real time while every
 * cadence boundary still performs the REAL lifecycle work (real enhance/invoke/unload and a
 * real Agent/Platform command-channel disconnect/recovery while the JVM stays alive). This is
 * the only test seam permitted by the M2-D brief ("Test-only
 * clock/cadence injection is allowed"); the fixed cadence constants and the production
 * {@link WallClock} default remain explicit and are verified by {@code SoakCadenceTest}.
 *
 * <p>The clock advances time ONLY via {@link #tick()} (called once per loop iteration after a
 * real invocation burst). {@link WallClock} needs no tick because real time advances on its
 * own; {@link AcceleratedClock} advances virtual time by {@code step} per tick. The harness
 * never busy-waits on a sleep to reach a cadence boundary: it runs real work, ticks, then
 * checks which cadence boundaries the new elapsed time has crossed.
 */
public sealed interface SoakClock permits SoakClock.WallClock, SoakClock.AcceleratedClock {

    /** Current time (real wall time, or virtual time for the accelerated test clock). */
    Instant now();

    /** Monotonic elapsed run time used for duration and cadence decisions. */
    Duration elapsed();

    /**
     * Advance the clock by one loop iteration's worth of time. A no-op for the production
     * {@link WallClock} (real time advances on its own); advances virtual time by {@code step}
     * for the {@link AcceleratedClock}.
     */
    void tick();

    /**
     * Reset the measurement origin after the harness has exercised its cold startup paths.
     * Warm-up work is real lifecycle work, but it is deliberately excluded from the requested
     * soak duration and from the fixed cadence counters.
     */
    void reset();

    /** The production wall clock: real time, no synthetic advance. Used by {@code main} / RC / RELEASE. */
    final class WallClock implements SoakClock {
        private long startedNanos = System.nanoTime();
        @Override public Instant now() { return Instant.now(); }
        @Override public Duration elapsed() {
            return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos));
        }
        @Override public void tick() { /* real time advances on its own; no synthetic step */ }
        @Override public void reset() { startedNanos = System.nanoTime(); }
    }

    /**
     * Test-only accelerated clock. Holds a virtual instant anchored at construction time and
     * advances it by {@code step} on every {@link #tick()}, so the fixed 1m/5m/30m cadence
     * fires over a handful of real milliseconds while each cadence boundary still does real
     * lifecycle work. Never used by the production runner.
     */
    final class AcceleratedClock implements SoakClock {
        private Instant current;
        private final Duration step;
        private Duration elapsed = Duration.ZERO;

        /**
         * @param start  the virtual start instant (the soak's t0)
         * @param step   virtual time advanced per {@link #tick()}; {@link SoakCadence#DOCUMENTED}
         *               cadence fires at its natural rate when {@code step} divides the cadence
         *               intervals (e.g. {@code PT1M} fires one summary per tick)
         */
        public AcceleratedClock(Instant start, Duration step) {
            if (step == null || step.isZero() || step.isNegative()) {
                throw new IllegalArgumentException("accelerated clock step must be positive");
            }
            this.current = start;
            this.step = step;
        }

        @Override public Instant now() { return current; }
        @Override public Duration elapsed() { return elapsed; }
        @Override public void tick() {
            current = current.plus(step);
            elapsed = elapsed.plus(step);
        }
        @Override public void reset() { elapsed = Duration.ZERO; }
    }
}
