package com.example.kairo.api.bytecode;

import java.util.Objects;

/**
 * A single diagnostic attached to a transformation result. Diagnostics are the
 * only free-form text channel on the frozen result DTO; structured bytecode
 * differences live on {@link BytecodeDiffResult}.
 *
 * @param severity          INFO, WARN or ERROR
 * @param code              stable machine-readable code, never blank
 * @param message           human-readable message, never blank
 * @param exceptionClassName nullable fully-qualified exception class name
 * @param detail            nullable extra detail (e.g. exception message)
 */
public record TransformationDiagnostic(
        Severity severity,
        String code,
        String message,
        String exceptionClassName,
        String detail
) {

    public enum Severity {
        INFO,
        WARN,
        ERROR
    }

    public TransformationDiagnostic {
        Objects.requireNonNull(severity, "severity");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    public static TransformationDiagnostic of(Severity severity, String code, String message) {
        return new TransformationDiagnostic(severity, code, message, null, null);
    }

    public static TransformationDiagnostic info(String code, String message) {
        return new TransformationDiagnostic(Severity.INFO, code, message, null, null);
    }

    public static TransformationDiagnostic warn(String code, String message) {
        return new TransformationDiagnostic(Severity.WARN, code, message, null, null);
    }

    public static TransformationDiagnostic error(String code, String message) {
        return new TransformationDiagnostic(Severity.ERROR, code, message, null, null);
    }

    public static TransformationDiagnostic error(String code, String message, Throwable throwable) {
        String exceptionClassName = throwable == null ? null : throwable.getClass().getName();
        String detail = throwable == null ? null : throwable.getMessage();
        return new TransformationDiagnostic(Severity.ERROR, code, message, exceptionClassName, detail);
    }
}
