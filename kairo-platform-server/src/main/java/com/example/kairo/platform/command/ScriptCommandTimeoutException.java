package com.example.kairo.platform.command;

import java.time.Duration;

/** Thrown by {@link ScriptSessionExchange#await} when the agent did not ack within the timeout. */
public class ScriptCommandTimeoutException extends RuntimeException {
    public ScriptCommandTimeoutException(Duration timeout) {
        super("Agent did not acknowledge the script command within " + timeout.toMillis() + "ms");
    }
}
