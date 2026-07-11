package com.autospec;

import com.autospec.dto.PaginationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaginationRequestTest {

    @Test
    void appliesDefaultsAndAcceptsBoundaries() {
        assertThat(PaginationRequest.of(null, null))
                .isEqualTo(new PaginationRequest(50, 0));
        assertThat(PaginationRequest.of(1, 0))
                .isEqualTo(new PaginationRequest(1, 0));
        assertThat(PaginationRequest.of(100, 25))
                .isEqualTo(new PaginationRequest(100, 25));
    }

    @Test
    void rejectsInvalidLimitWithStableBadRequestMessage() {
        assertThatThrownBy(() -> PaginationRequest.of(0, 0))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getReason()).isEqualTo("limit must be between 1 and 100");
                });
        assertThatThrownBy(() -> PaginationRequest.of(101, 0))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getReason()).isEqualTo("limit must be between 1 and 100");
                });
    }

    @Test
    void rejectsInvalidOffsetWithStableBadRequestMessage() {
        assertThatThrownBy(() -> PaginationRequest.of(50, -1))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getReason()).isEqualTo("offset must be greater than or equal to 0");
                });
    }
}
