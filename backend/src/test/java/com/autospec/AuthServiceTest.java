package com.autospec;

import com.autospec.entity.UserAccount;
import com.autospec.entity.UserSession;
import com.autospec.service.AuthService;
import com.autospec.service.UserAccountService;
import com.autospec.service.UserSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthServiceTest {

    private static final String SESSION_HEADER = "X-AutoSpec-Session-Token";

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    void logoutRevokesTheCurrentSession() throws Exception {
        UserAccount user = createUser("logout-session-");
        String token = authService.issueSession(user);

        mockMvc.perform(post("/api/auth/logout")
                        .header(SESSION_HEADER, token))
                .andExpect(status().isNoContent());

        UserSession persisted = userSessionService.lambdaQuery()
                .eq(UserSession::getUserId, user.getId())
                .one();
        assertThat(persisted.getRevokedAt()).isNotNull();
        assertThatThrownBy(() -> authService.requireSessionUserId(token))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private UserAccount createUser(String usernamePrefix) {
        UserAccount user = new UserAccount();
        user.setUsername(usernamePrefix + System.nanoTime());
        user.setDisplayName("Session User");
        user.setPasswordHash("not-used-by-this-test");
        user.setEnabled(true);
        userAccountService.save(user);
        return user;
    }
}
