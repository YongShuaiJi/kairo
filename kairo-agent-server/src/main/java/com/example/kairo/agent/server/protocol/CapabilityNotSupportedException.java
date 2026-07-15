package com.example.kairo.agent.server.protocol;

/**
 * Raised when the platform dispatches a command the agent did not advertise as
 * a capability (V1.6 &sect;5.2). The poller converts this into a structured
 * {@code CAPABILITY_NOT_SUPPORTED} ack (category=CAPABILITY) instead of a
 * generic failure, so the platform can degrade gracefully.
 */
public final class CapabilityNotSupportedException extends RuntimeException {

    private final String commandType;

    public CapabilityNotSupportedException(String commandType) {
        super("Agent does not advertise capability: " + commandType);
        this.commandType = commandType;
    }

    public String commandType() {
        return commandType;
    }
}
