package com.example.kairo.agent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-A &sect;8.1: the agent echoes the dispatch epoch ({@code attempts}) it polled as
 * {@code expectedAttempts} on <em>every</em> ack path -- success, capability failure and exception
 * failure -- so the platform can fence out a stale owner whose lease was reclaimed by a re-dispatch.
 *
 * <p>{@code pollOnce} builds all three ack bodies through the single {@link PlatformCommandPoller#ackBody}
 * helper, reading the epoch via {@link PlatformCommandPoller#expectedAttempts}. Exercising those
 * pure units directly is the faithful contract (no HTTP, no real JVM): they are exactly what the
 * polling loop composes for each ack path. This is the §8.1 test the verification command names
 * explicitly ({@code PlatformCommandPollerEpochTest}); the plan's coverage list refers to it as
 * {@code AgentCommandPollerEpochTest} -- the same poller-epoch test under an equivalent name.
 */
class PlatformCommandPollerEpochTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successAckEchoesExpectedAttempts() {
        long epoch = 2L;
        Map<String, Object> body = PlatformCommandPoller.ackBody(epoch, "ACKED",
                "agent command applied", Map.of("disabled", true), null);
        assertThat(body.get("status")).isEqualTo("ACKED");
        assertThat(body.get("expectedAttempts")).isEqualTo(epoch);
        assertThat(body.get("result")).isEqualTo(Map.of("disabled", true));
        assertThat(body).doesNotContainKey("errorMessage");
    }

    @Test
    void capabilityFailureAckEchoesExpectedAttempts() {
        long epoch = 3L;
        Map<String, Object> structured = Map.of(
                "code", "CAPABILITY_NOT_SUPPORTED",
                "category", "CAPABILITY",
                "commandType", "SCRIPT_FUTURE",
                "retryable", false);
        Map<String, Object> body = PlatformCommandPoller.ackBody(epoch, "FAILED",
                "capability not supported", structured,
                "agent does not advertise capability SCRIPT_FUTURE");
        assertThat(body.get("status")).isEqualTo("FAILED");
        assertThat(body.get("expectedAttempts")).isEqualTo(epoch);
        assertThat(body.get("errorMessage")).asString().contains("SCRIPT_FUTURE");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertThat(result.get("code")).isEqualTo("CAPABILITY_NOT_SUPPORTED");
        assertThat(result.get("commandType")).isEqualTo("SCRIPT_FUTURE");
    }

    @Test
    void exceptionFailureAckEchoesExpectedAttempts() {
        long epoch = 1L;
        Map<String, Object> body = PlatformCommandPoller.ackBody(epoch, "FAILED",
                "agent command failed", null, "RuntimeException: boom");
        assertThat(body.get("status")).isEqualTo("FAILED");
        assertThat(body.get("expectedAttempts")).isEqualTo(epoch);
        assertThat(body.get("errorMessage")).isEqualTo("RuntimeException: boom");
        assertThat(body).doesNotContainKey("result");
    }

    @Test
    void expectedAttemptsReadsTheDispatchEpochFromThePolledCommand() throws Exception {
        JsonNode command = mapper.readTree(
                "{\"id\":\"cmd-1\",\"attempts\":4,\"status\":\"DISPATCHED\"}");
        assertThat(PlatformCommandPoller.expectedAttempts(command)).isEqualTo(4L);
    }

    @Test
    void missingAttemptsResolvesToZeroSoAStaleOwnerIsFencedOut() throws Exception {
        // A V1.7 platform always returns attempts; a malformed/legacy command missing it resolves
        // to 0, which the platform fences out (attempts != 0) rather than silently accepting.
        JsonNode command = mapper.readTree("{\"id\":\"cmd-1\"}");
        assertThat(PlatformCommandPoller.expectedAttempts(command)).isZero();
    }
}
