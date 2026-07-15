package com.example.kairo.api.paging;

import java.util.List;
import java.util.Objects;

/**
 * Unified cursor-paginated list response (V1.6 &sect;2.2 "列表统一 cursor 或 page 模型").
 * Every list endpoint in the V1 API returns this shape; resource families do not
 * mix pagination formats.
 *
 * @param items      the page of results
 * @param nextCursor opaque cursor to pass back as {@code cursor} for the next page,
 *                   or {@code null} when there are no more items
 * @param hasMore    convenience flag derived from {@code nextCursor}
 * @param total      best-effort total count, or {@code -1} when unknown/expensive
 */
public record Page<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore,
        long total
) {
    public Page {
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;
        hasMore = nextCursor != null || hasMore;
    }

    public static <T> Page<T> of(List<T> items) {
        return new Page<>(items, null, false, -1);
    }

    public static <T> Page<T> of(List<T> items, String nextCursor, long total) {
        return new Page<>(items, nextCursor, nextCursor != null, total);
    }
}
