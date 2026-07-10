package com.example.kairo.agent.core.bytecode.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, normalized structural model of one class file produced by
 * {@link BytecodeNormalizer}. Holds only value types (strings/ints), so it never
 * references ASM {@code Label} or {@code Class} objects and is safe to retain.
 *
 * <p>Equality is defined by {@link #canonicalLines()} - a deterministic list of
 * token lines that ignores constant-pool indices, stack-map frames and debug
 * info (line numbers, source file, local-variable names) while preserving
 * instructions, descriptors, signatures, exception tables, annotations and
 * class/field structure. Two semantically-equal class files always produce
 * equal canonical lines even when their raw bytes differ.
 */
final class NormalizedClass {

    final String name;
    final String superName;
    final String signature;
    final int access;
    final List<String> interfaces;
    final List<String> annotations;
    final List<NormalizedField> fields;
    final List<NormalizedMethod> methods;

    NormalizedClass(String name, String superName, String signature, int access,
                    List<String> interfaces, List<String> annotations,
                    List<NormalizedField> fields, List<NormalizedMethod> methods) {
        this.name = name;
        this.superName = superName;
        this.signature = signature;
        this.access = access;
        this.interfaces = List.copyOf(interfaces);
        this.annotations = List.copyOf(annotations);
        this.fields = List.copyOf(fields);
        this.methods = List.copyOf(methods);
    }

    /**
     * Deterministic token-line view of the whole class. Method bodies are
     * emitted in declaration order; the list is the authoritative equality key.
     */
    List<String> canonicalLines() {
        List<String> lines = new ArrayList<>();
        lines.add("CLASS " + name + " extends " + superName + " access=" + access
                + " sig=" + signature + " interfaces=" + interfaces + " annotations=" + annotations);
        for (NormalizedField field : fields) {
            lines.add("  " + field.canonicalLine());
        }
        for (NormalizedMethod method : methods) {
            lines.add("  METHOD " + method.name + method.desc
                    + " access=" + method.access + " sig=" + method.signature
                    + " throws=" + method.exceptions + " annotations=" + method.annotations);
            for (String tc : method.tryCatch) {
                lines.add("    " + tc);
            }
            for (String insn : method.instructions) {
                lines.add("    " + insn);
            }
        }
        return lines;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NormalizedClass that)) {
            return false;
        }
        return canonicalLines().equals(that.canonicalLines());
    }

    @Override
    public int hashCode() {
        return canonicalLines().hashCode();
    }

    static final class NormalizedField {
        final int access;
        final String name;
        final String desc;
        final String signature;
        final String value;

        NormalizedField(int access, String name, String desc, String signature, String value) {
            this.access = access;
            this.name = name;
            this.desc = desc;
            this.signature = signature;
            this.value = value;
        }

        String canonicalLine() {
            return "FIELD access=" + access + " " + name + " " + desc + " sig=" + signature + " value=" + value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof NormalizedField that)) {
                return false;
            }
            return access == that.access && Objects.equals(name, that.name)
                    && Objects.equals(desc, that.desc) && Objects.equals(signature, that.signature)
                    && Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(access, name, desc, signature, value);
        }
    }

    static final class NormalizedMethod {
        final int access;
        final String name;
        final String desc;
        final String signature;
        final List<String> exceptions;
        final List<String> annotations;
        final List<String> tryCatch;
        final List<String> instructions;

        NormalizedMethod(int access, String name, String desc, String signature,
                         List<String> exceptions, List<String> annotations,
                         List<String> tryCatch, List<String> instructions) {
            this.access = access;
            this.name = name;
            this.desc = desc;
            this.signature = signature;
            this.exceptions = List.copyOf(exceptions);
            this.annotations = List.copyOf(annotations);
            this.tryCatch = List.copyOf(tryCatch);
            this.instructions = List.copyOf(instructions);
        }

        String key() {
            return name + desc;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof NormalizedMethod that)) {
                return false;
            }
            return access == that.access && Objects.equals(name, that.name)
                    && Objects.equals(desc, that.desc) && Objects.equals(signature, that.signature)
                    && exceptions.equals(that.exceptions) && annotations.equals(that.annotations)
                    && tryCatch.equals(that.tryCatch) && instructions.equals(that.instructions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(access, name, desc, signature, exceptions, annotations, tryCatch, instructions);
        }
    }
}
