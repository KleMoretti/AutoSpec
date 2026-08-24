package com.autospec.dto;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record CursorPaginationRequest(int limit, Long afterId) {
    private static final String VERSION_PREFIX = "v1:";
    private static final int MAX_CURSOR_LENGTH = 128;

    public static CursorPaginationRequest of(Integer limit, String cursor) {
        int resolvedLimit = limit == null ? PaginationRequest.DEFAULT_LIMIT : limit;
        if (resolvedLimit < 1 || resolvedLimit > PaginationRequest.MAX_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and 100"
            );
        }
        return new CursorPaginationRequest(resolvedLimit, decode(cursor));
    }

    public int fetchLimit() {
        return limit + 1;
    }

    public static String encode(long id) {
        if (id < 1) {
            throw new IllegalArgumentException("Cursor id must be positive");
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((VERSION_PREFIX + id).getBytes(StandardCharsets.UTF_8));
    }

    private static Long decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8
            );
            if (!value.startsWith(VERSION_PREFIX)) {
                throw invalidCursor();
            }
            long id = Long.parseLong(value.substring(VERSION_PREFIX.length()));
            if (id < 1) {
                throw invalidCursor();
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is invalid");
    }
}
