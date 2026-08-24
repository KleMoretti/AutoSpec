package com.autospec.dto;

import java.util.List;

public record CursorPageResponse<T>(List<T> items, String nextCursor, boolean hasMore) {
    public CursorPageResponse {
        items = List.copyOf(items);
    }
}
