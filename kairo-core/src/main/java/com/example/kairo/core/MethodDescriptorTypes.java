package com.example.kairo.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a JVM method descriptor into reflective parameter and return types
 * using a {@link ClassLoader}. {@code kairo-core} does not depend on ASM, so
 * this is a minimal, spec-correct parser used by {@link DecisionValidator} for
 * call-site argument / return-value type checking where no reflective
 * {@code Method} for the callee exists &mdash; only the descriptor carried by a
 * {@link com.example.kairo.api.CallSiteSelector}.
 */
public final class MethodDescriptorTypes {

    private MethodDescriptorTypes() {
    }

    public static Class<?>[] parameterTypes(String descriptor, ClassLoader loader) {
        return parse(descriptor, loader, true);
    }

    public static Class<?> returnType(String descriptor, ClassLoader loader) {
        return parse(descriptor, loader, false)[0];
    }

    private static Class<?>[] parse(String descriptor, ClassLoader loader, boolean parameters) {
        if (descriptor == null || descriptor.length() < 2 || descriptor.charAt(0) != '(') {
            throw new IllegalArgumentException("Invalid descriptor: " + descriptor);
        }
        int close = descriptor.indexOf(')');
        if (close < 0) {
            throw new IllegalArgumentException("Invalid descriptor: " + descriptor);
        }
        if (parameters) {
            List<Class<?>> types = new ArrayList<>();
            int i = 1;
            while (i < close) {
                int[] next = parseOne(descriptor, i, loader);
                types.add(toClass(descriptor.substring(i, next[0]), loader));
                i = next[0];
            }
            return types.toArray(new Class<?>[0]);
        }
        String returnType = descriptor.substring(close + 1);
        return new Class<?>[]{toClass(returnType, loader)};
    }

    private static int[] parseOne(String descriptor, int start, ClassLoader loader) {
        int i = start;
        while (descriptor.charAt(i) == '[') {
            i++;
        }
        char c = descriptor.charAt(i);
        if (c == 'L') {
            int semi = descriptor.indexOf(';', i);
            if (semi < 0) {
                throw new IllegalArgumentException("Invalid object type in descriptor: " + descriptor);
            }
            return new int[]{semi + 1};
        }
        return new int[]{i + 1};
    }

    private static Class<?> toClass(String type, ClassLoader loader) {
        String normalized = type;
        int dims = 0;
        while (normalized.startsWith("[")) {
            dims++;
            normalized = normalized.substring(1);
        }
        Class<?> element;
        if (normalized.startsWith("L") && normalized.endsWith(";")) {
            String binary = normalized.substring(1, normalized.length() - 1).replace('/', '.');
            element = resolve(binary, loader);
        } else {
            element = primitive(normalized);
        }
        if (dims == 0) {
            return element;
        }
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < dims; d++) {
            sb.append('[');
        }
        if (element == int.class) sb.append('I');
        else if (element == long.class) sb.append('J');
        else if (element == boolean.class) sb.append('Z');
        else if (element == byte.class) sb.append('B');
        else if (element == char.class) sb.append('C');
        else if (element == short.class) sb.append('S');
        else if (element == float.class) sb.append('F');
        else if (element == double.class) sb.append('D');
        else if (element == void.class) sb.append('V');
        else sb.append('L').append(element.getName().replace('.', '/')).append(';');
        try {
            return Class.forName(sb.toString(), false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot resolve array type " + type, e);
        }
    }

    private static Class<?> resolve(String binary, ClassLoader loader) {
        ClassLoader cl = loader == null ? ClassLoader.getSystemClassLoader() : loader;
        try {
            return Class.forName(binary, false, cl);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot resolve type " + binary, e);
        }
    }

    private static Class<?> primitive(String code) {
        return switch (code) {
            case "I" -> int.class;
            case "J" -> long.class;
            case "Z" -> boolean.class;
            case "B" -> byte.class;
            case "C" -> char.class;
            case "S" -> short.class;
            case "F" -> float.class;
            case "D" -> double.class;
            case "V" -> void.class;
            default -> throw new IllegalArgumentException("Unknown primitive code: " + code);
        };
    }
}
