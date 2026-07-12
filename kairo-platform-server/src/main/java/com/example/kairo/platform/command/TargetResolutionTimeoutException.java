package com.example.kairo.platform.command;

import java.time.Duration;

/** Thrown by {@link TargetResolutionExchange#await} when the agent did not ack within the timeout. */
public class TargetResolutionTimeoutException extends RuntimeException {
    public TargetResolutionTimeoutException(Duration timeout) {
        super("Agent did not acknowledge the target resolution command within " + timeout.toMillis() + "ms");
    }
}
