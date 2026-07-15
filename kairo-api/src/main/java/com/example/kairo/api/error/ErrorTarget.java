package com.example.kairo.api.error;

import java.util.Objects;

/**
 * Locates the offending field of a validation/semantic error (V1.6 &sect;2.4
 * "field/path/location").
 *
 * @param field    logical field name, e.g. {@code target.methodName}
 * @param path     JSON pointer into the request body, e.g. {@code /target/methodName}
 * @param location where the value lives: {@code body}, {@code query}, {@code header} or {@code path}
 */
public record ErrorTarget(String field, String path, String location) {

    public ErrorTarget {
        field = field == null ? "" : field;
        path = path == null ? "" : path;
        location = location == null ? "" : location;
    }

    public static ErrorTarget bodyField(String field) {
        Objects.requireNonNull(field, "field");
        return new ErrorTarget(field, "/" + field.replace('.', '/'), "body");
    }

    public static ErrorTarget bodyPath(String jsonPointer) {
        return new ErrorTarget("", jsonPointer, "body");
    }
}
