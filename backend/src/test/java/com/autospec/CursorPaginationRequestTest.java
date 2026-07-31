package com.autospec;

import com.autospec.dto.CursorPaginationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorPaginationRequestTest {

    @Test
    void roundTripsAnOpaquePositiveIdCursor() {
        String cursor = CursorPaginationRequest.encode(42L);

        assertThat(cursor).doesNotContain("42");
        assertThat(CursorPaginationRequest.of(25, cursor))
                .isEqualTo(new CursorPaginationRequest(25, 42L));
        assertThat(CursorPaginationRequest.of(null, null))
                .isEqualTo(new CursorPaginationRequest(50, null));
    }

    @Test
    void rejectsMalformedOrUnsupportedCursors() {
        assertThatThrownBy(() -> CursorPaginationRequest.of(25, "not-base64!"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo("cursor is invalid");
                });
        String unsupported = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v2:42".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CursorPaginationRequest.of(25, unsupported))
                .isInstanceOf(ResponseStatusException.class);
    }
}
