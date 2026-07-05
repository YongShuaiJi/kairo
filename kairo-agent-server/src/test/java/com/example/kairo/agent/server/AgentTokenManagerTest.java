package com.example.kairo.agent.server;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTokenManagerTest {

    @Test
    void rejectsExpiredToken() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-18T00:00:00Z"));
        try (AgentTokenManager manager = new AgentTokenManager("initial-token", Duration.ofMinutes(15), clock)) {
            assertThat(manager.accepts("initial-token")).isTrue();

            clock.instant = clock.instant.plus(Duration.ofMinutes(15));

            assertThat(manager.accepts("initial-token")).isFalse();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
