package com.example.kairo.agent.server;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class AgentTokenManager implements AutoCloseable {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Duration ttl;
    private final Clock clock;
    private final AtomicReference<TokenState> state;
    private final ScheduledExecutorService executor;

    AgentTokenManager(String initialToken, Duration ttl) {
        this(initialToken, ttl, Clock.systemUTC());
    }

    AgentTokenManager(String initialToken, Duration ttl, Clock clock) {
        if (initialToken == null || initialToken.isBlank()) {
            throw new IllegalArgumentException("Agent token must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Agent token TTL must be positive");
        }
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.state = new AtomicReference<>(new TokenState(initialToken, clock.instant().plus(ttl)));
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kairo-agent-token-rotator");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start(Consumer<TokenState> publisher) {
        Objects.requireNonNull(publisher, "publisher").accept(state.get());
        long intervalMillis = Math.max(1_000L, ttl.toMillis() / 2L);
        executor.scheduleWithFixedDelay(() -> rotateSafely(publisher),
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    boolean accepts(String candidate) {
        TokenState current = state.get();
        return candidate != null
                && clock.instant().isBefore(current.expiresAt())
                && MessageDigest.isEqual(
                current.token().getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    TokenState current() {
        return state.get();
    }

    private void rotateSafely(Consumer<TokenState> publisher) {
        TokenState next = new TokenState(generateToken(), clock.instant().plus(ttl));
        try {
            publisher.accept(next);
            state.set(next);
        } catch (RuntimeException ignored) {
            // Keep the current token until its original expiry; authorization then fails closed.
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    record TokenState(String token, Instant expiresAt) {
    }
}
