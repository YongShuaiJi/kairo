package com.example.kairo.api.paging;

/**
 * Cursor pagination request (V1.6 &sect;2.2). {@code limit} is clamped to
 * {@code [1, 200]}; {@code cursor} is the opaque {@link Page#nextCursor()}.
 */
public record PageRequest(Integer limit, String cursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public int effectiveLimit() {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    public String effectiveCursor() {
        return cursor == null || cursor.isBlank() ? null : cursor;
    }
}
