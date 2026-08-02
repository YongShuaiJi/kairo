package com.example.kairo.api.support;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M4-C &sect;11.3 unit tests for {@link BoundedReads}: the bounded reader reads at most
 * {@code maxBytes + 1}, reports too-large without retaining the rest, and never reads far past the cap.
 */
class BoundedReadsTest {

    @Test
    void readsExactFitBody() throws IOException {
        byte[] body = "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        BoundedReads.ReadResult result = BoundedReads.readAtMost(new ByteArrayInputStream(body), 1024);
        assertThat(result.isTooLarge()).isFalse();
        assertThat(result.bytes()).isEqualTo(body);
    }

    @Test
    void readsBodyAtExactCap() throws IOException {
        byte[] body = new byte[1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i & 0x7f);
        }
        BoundedReads.ReadResult result = BoundedReads.readAtMost(new ByteArrayInputStream(body), 1024);
        // Exactly maxBytes is not oversized (the +1 probe byte read returns EOF).
        assertThat(result.isTooLarge()).isFalse();
        assertThat(result.bytes()).isEqualTo(body);
    }

    @Test
    void reportsTooLargeAndDoesNotRetainRest() throws IOException {
        byte[] body = new byte[10 * 1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i & 0x7f);
        }
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        BoundedReads.ReadResult result = BoundedReads.readAtMost(in, 1024);
        assertThat(result.isTooLarge()).isTrue();
        assertThat(result.bytes()).isEmpty();
        // The reader stopped at the cap; the rest of the stream is still available (not buffered).
        assertThat(in.available()).isGreaterThan(0);
    }

    @Test
    void neverReadsFarPastCap() throws IOException {
        // A stream that yields unlimited bytes; the reader must read at most maxBytes + 1 and stop.
        CountingStream in = new CountingStream(0x5A, Integer.MAX_VALUE);
        BoundedReads.ReadResult result = BoundedReads.readAtMost(in, 1024);
        assertThat(result.isTooLarge()).isTrue();
        // At most maxBytes + 1 (plus at most one partial chunk already in flight) is read; never the
        // full (effectively unbounded) stream.
        assertThat(in.readBytes).isLessThanOrEqualTo(1024L + 1L + 8192L);
    }

    @Test
    void emptyBodyIsOk() throws IOException {
        BoundedReads.ReadResult result = BoundedReads.readAtMost(new ByteArrayInputStream(new byte[0]), 1024);
        assertThat(result.isTooLarge()).isFalse();
        assertThat(result.bytes()).isEmpty();
    }

    @Test
    void deadlineClosesAndUnblocksStalledStream() {
        BlockingStream in = new BlockingStream();
        long start = System.nanoTime();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        BoundedReads.readAtMost(in, 1024, Duration.ofMillis(50)))
                .isInstanceOf(BoundedReads.ReadTimeoutException.class);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertThat(in.closed).isTrue();
        assertThat(elapsedMillis).isLessThan(1_000L);
    }

    /** InputStream that yields the same byte forever, counting how many bytes were consumed. */
    private static final class CountingStream extends InputStream {
        private final int fill;
        private final int limit;
        long readBytes;

        CountingStream(int fill, int limit) {
            this.fill = fill;
            this.limit = limit;
        }

        @Override
        public int read() {
            if (readBytes >= limit) {
                return -1;
            }
            readBytes++;
            return fill & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (readBytes >= limit) {
                return -1;
            }
            int n = (int) Math.min(len, limit - readBytes);
            for (int i = 0; i < n; i++) {
                b[off + i] = (byte) fill;
            }
            readBytes += n;
            return n;
        }
    }

    private static final class BlockingStream extends InputStream {
        private boolean closed;

        @Override
        public synchronized int read() throws IOException {
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            throw new IOException("closed");
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            return read();
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }
}
