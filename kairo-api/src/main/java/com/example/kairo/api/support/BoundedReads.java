package com.example.kairo.api.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded byte-reader for V1.7 M4-C &sect;11.3 support-bundle sources. Reads at most {@code maxBytes + 1}
 * bytes from an {@link InputStream} and reports whether the source exceeded the cap, without retaining the
 * rest of the stream. Pure JDK &mdash; no HTTP &mdash; so it is shared by the SDK bounded-GET seam
 * ({@code KairoClient}) and the {@code kairo-ops} bounded Agent reader. The caller owns closing the
 * stream; this reader reads up to the cap and returns.
 *
 * <p>The {@code +1} byte lets the caller distinguish an exact-fit body (returns {@link ReadResult#ok})
 * from an oversized body (returns {@link ReadResult#tooLarge}) without buffering the full response.
 */
public final class BoundedReads {

    private static final ScheduledExecutorService DEADLINE_CLOSER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kairo-bounded-read-deadline");
                thread.setDaemon(true);
                return thread;
            });

    private BoundedReads() {
    }

    /** Result of a bounded read: either the (at most {@code maxBytes}) bytes read, or a too-large signal. */
    public static final class ReadResult {
        private final boolean tooLarge;
        private final byte[] bytes;

        private ReadResult(boolean tooLarge, byte[] bytes) {
            this.tooLarge = tooLarge;
            this.bytes = bytes;
        }

        static ReadResult ok(byte[] bytes) {
            return new ReadResult(false, bytes);
        }

        static ReadResult tooLarge() {
            return new ReadResult(true, new byte[0]);
        }

        /** {@code true} if the source exceeded the cap; the bytes are empty and must not be used. */
        public boolean isTooLarge() {
            return tooLarge;
        }

        /** The bytes read (at most {@code maxBytes}); empty when {@link #isTooLarge()} is {@code true}. */
        public byte[] bytes() {
            return bytes;
        }
    }

    /**
     * Read at most {@code maxBytes + 1} bytes from {@code in}. If the stream yields more than
     * {@code maxBytes} bytes, returns {@link ReadResult#tooLarge} (the partial bytes are discarded and the
     * rest of the stream is left unread for the caller to close). Otherwise returns the bytes read. Memory
     * is bounded to {@code maxBytes + 1}.
     */
    public static ReadResult readAtMost(InputStream in, long maxBytes) throws IOException {
        Objects.requireNonNull(in, "in");
        if (maxBytes < 0L || maxBytes == Long.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes must be between 0 and Long.MAX_VALUE - 1");
        }
        long limit = Math.max(0L, maxBytes) + 1L;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[(int) Math.min(8192L, Math.max(1L, limit))];
        long total = 0;
        int n;
        while (total < limit) {
            int max = (int) Math.min(buf.length, limit - total);
            n = in.read(buf, 0, max);
            if (n == -1) {
                break;
            }
            baos.write(buf, 0, n);
            total += n;
        }
        if (total > maxBytes) {
            return ReadResult.tooLarge();
        }
        return ReadResult.ok(baos.toByteArray());
    }

    /**
     * Deadline-aware variant used for HTTP response streams. {@code HttpRequest.timeout} does not
     * reliably bound the later consumption of a body returned by {@code ofInputStream()}; therefore a
     * response that sends headers and then stalls must also be interrupted while it is being read.
     * The deadline task closes the stream, which cancels the HTTP body subscription and unblocks the
     * reader. The caller still owns the surrounding try-with-resources close.
     */
    public static ReadResult readAtMost(InputStream in, long maxBytes, Duration timeout) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException ignored) {
            timeoutNanos = Long.MAX_VALUE;
        }
        AtomicBoolean expired = new AtomicBoolean(false);
        ScheduledFuture<?> closer = DEADLINE_CLOSER.schedule(() -> {
            expired.set(true);
            try {
                in.close();
            } catch (IOException ignored) {
                // The reader maps the deadline to ReadTimeoutException; close diagnostics are not exposed.
            }
        }, Math.max(1L, timeoutNanos), TimeUnit.NANOSECONDS);
        try {
            ReadResult result = readAtMost(in, maxBytes);
            if (expired.get()) {
                throw new ReadTimeoutException();
            }
            return result;
        } catch (IOException e) {
            if (expired.get()) {
                throw new ReadTimeoutException(e);
            }
            throw e;
        } finally {
            closer.cancel(false);
        }
    }

    /** Raised when the deadline-aware reader had to close a stalled source stream. */
    public static final class ReadTimeoutException extends IOException {
        public ReadTimeoutException() {
            super("bounded read deadline expired");
        }

        public ReadTimeoutException(Throwable cause) {
            super("bounded read deadline expired", cause);
        }
    }
}
