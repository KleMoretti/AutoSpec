package com.autospec.dto;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record PaginationRequest(int limit, int offset) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int DEFAULT_OFFSET = 0;
    public static final int MAX_LIMIT = 100;

    public static PaginationRequest of(Integer limit, Integer offset) {
        int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
        int resolvedOffset = offset == null ? DEFAULT_OFFSET : offset;
        if (resolvedLimit < 1 || resolvedLimit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        if (resolvedOffset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must be greater than or equal to 0");
        }
        return new PaginationRequest(resolvedLimit, resolvedOffset);
    }
}
