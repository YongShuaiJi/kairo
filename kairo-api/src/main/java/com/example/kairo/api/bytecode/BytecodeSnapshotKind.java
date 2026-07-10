package com.example.kairo.api.bytecode;

/**
 * The three distinguished kinds of bytecode a transformation records.
 *
 * <p>The page may say "before enhancement", but the underlying field must use
 * these precise kinds because other agents' instrumentation order makes
 * "original bytecode" ambiguous:
 *
 * <ul>
 *   <li>{@link #INPUT} - the bytes Kairo's transformer received this time;</li>
 *   <li>{@link #PLANNED} - the bytes a read-only preview expects to produce;</li>
 *   <li>{@link #APPLIED} - the bytes re-fetched from the JVM after the
 *       transformation actually completed.</li>
 * </ul>
 */
public enum BytecodeSnapshotKind {
    INPUT,
    PLANNED,
    APPLIED
}
