package com.example.kairo.platform.command;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptSessionExchangeTest {

    @Test
    void awaitReturnsResultCompletedByAck() {
        ScriptSessionExchange exchange = new ScriptSessionExchange();
        exchange.register("c1", null);
        exchange.complete("c1", Map.of("status", "VALIDATED", "hitCount", 0));
        assertThat(exchange.await("c1", Duration.ofSeconds(1)))
                .containsEntry("status", "VALIDATED");
        assertThat(exchange.pendingCount()).isZero();
    }

    @Test
    void failSurfacesAsScriptCommandFailureCarryingResult() {
        ScriptSessionExchange exchange = new ScriptSessionExchange();
        exchange.register("c2", null);
        exchange.fail("c2", "compile error at line 3",
                Map.of("diagnostics", java.util.List.of(Map.of("code", "SCRIPT_COMPILE_ERROR"))));
        assertThatThrownBy(() -> exchange.await("c2", Duration.ofSeconds(1)))
                .isInstanceOf(ScriptCommandFailure.class)
                .hasMessageContaining("compile error at line 3")
                .satisfies(t -> assertThat(((ScriptCommandFailure) t).result())
                        .containsKey("diagnostics"));
        assertThat(exchange.pendingCount()).isZero();
    }

    @Test
    void timeoutThrowsAndCleansUp() {
        ScriptSessionExchange exchange = new ScriptSessionExchange();
        exchange.register("c3", null);
        assertThatThrownBy(() -> exchange.await("c3", Duration.ofMillis(10)))
                .isInstanceOf(ScriptCommandTimeoutException.class);
        assertThat(exchange.pendingCount()).isZero();
    }

    @Test
    void earlyCompleteBeforeRegisterIsDeliveredToLateAwaiter() {
        ScriptSessionExchange exchange = new ScriptSessionExchange();
        exchange.complete("c4", Map.of("status", "APPLIED", "hitCount", 1));
        exchange.register("c4", null);
        assertThat(exchange.await("c4", Duration.ofSeconds(1)))
                .containsEntry("status", "APPLIED");
    }

    @Test
    void enrichPayloadSplicesTransientScriptButPersistedPayloadStaysHashOnly() {
        ScriptSessionExchange exchange = new ScriptSessionExchange();
        exchange.register("c5", "return 42");
        Map<String, Object> persisted = Map.of("commandType", "SCRIPT_SESSION_CREATE",
                "scriptHash", "abc", "sessionId", "s1");
        Map<String, Object> enriched = exchange.enrichPayload("c5", persisted);
        assertThat(enriched).containsEntry("script", "return 42").containsEntry("scriptHash", "abc");
        // The persisted map is untouched: the script source never lives on the durable copy.
        assertThat(persisted).doesNotContainKey("script");
        exchange.remove("c5");
    }

    @Test
    void enrichPayloadIsNoOpWhenNoScriptRegistered() {
        ScriptSessionExchange exchange = new ScriptSessionExchange();
        exchange.register("c6", null);
        Map<String, Object> persisted = Map.of("commandType", "SCRIPT_SESSION_VALIDATE", "sessionId", "s1");
        assertThat(exchange.enrichPayload("c6", persisted)).isSameAs(persisted);
    }
}
