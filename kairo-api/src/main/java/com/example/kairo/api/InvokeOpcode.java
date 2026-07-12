package com.example.kairo.api;

/**
 * JVM {@code invoke*} opcodes that a call-site enhancement can attach to.
 *
 * <p>The integer values are fixed by the JVM specification, so this enum carries
 * them directly rather than depending on any ASM type &mdash; {@code kairo-api}
 * remains free of bytecode-manipulation dependencies. {@link #INVOKEDYNAMIC} is
 * declared so the agent can return a clear {@code unsupported} diagnostic, but
 * V1.3 does not enhance invokedynamic call sites.
 */
public enum InvokeOpcode {

    INVOKEVIRTUAL(182),
    INVOKESPECIAL(183),
    INVOKESTATIC(184),
    INVOKEINTERFACE(185),
    INVOKEDYNAMIC(186);

    private final int opcode;

    InvokeOpcode(int opcode) {
        this.opcode = opcode;
    }

    public int opcode() {
        return opcode;
    }

    public static InvokeOpcode fromOpcode(int opcode) {
        for (InvokeOpcode value : values()) {
            if (value.opcode == opcode) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported invoke opcode: " + opcode);
    }

    /** Whether V1.3 can enhance a call site with this opcode. */
    public boolean isSupported() {
        return this != INVOKEDYNAMIC;
    }
}
