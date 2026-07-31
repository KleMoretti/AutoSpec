package com.autospec;

import com.autospec.entity.UserAccount;
import com.autospec.entity.UserSession;
import com.autospec.service.AuthService;
import com.autospec.service.UserAccountService;
import com.autospec.service.UserSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserSessionService userSessionService;

    @Test
    void persistsHashedSessionAcrossServiceInstancesAndRejectsItAfterExpiry() {
        UserAccount user = new UserAccount();
        user.setUsername("persistent-session-" + System.nanoTime());
        user.setDisplayName("Persistent Session User");
        user.setPasswordHash("not-used-by-this-test");
        user.setEnabled(true);
        userAccountService.save(user);

        String token = authService.issueSession(user);

        UserSession persisted = userSessionService.lambdaQuery()
                .eq(UserSession::getUserId, user.getId())
                .one();
        assertThat(persisted.getTokenHash())
                .hasSize(64)
                .isNotEqualTo(token);
        assertThat(persisted.getExpiresAt()).isAfter(persisted.getCreatedAt());

        AuthService secondInstance = new AuthService(
                userAccountService,
                userSessionService,
                Duration.ofHours(12)
        );
        assertThat(secondInstance.requireSessionUserId(token)).isEqualTo(user.getId());

        persisted.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        userSessionService.updateById(persisted);

        assertThatThrownBy(() -> secondInstance.requireSessionUserId(token))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
