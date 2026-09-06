package com.autospec.service;

import com.autospec.entity.UserAccount;
import com.autospec.entity.UserSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthService {

    private final UserAccountService userAccountService;
    private final UserSessionService userSessionService;
    private final LoginRateLimiter loginRateLimiter;
    private final Duration sessionTtl;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            UserAccountService userAccountService,
            UserSessionService userSessionService,
            LoginRateLimiter loginRateLimiter,
            @Value("${autospec.auth.session.ttl:12h}") Duration sessionTtl
    ) {
        if (sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("Session TTL must be positive");
        }
        this.userAccountService = userAccountService;
        this.userSessionService = userSessionService;
        this.loginRateLimiter = loginRateLimiter;
        this.sessionTtl = sessionTtl;
    }

    @Transactional
    public UserAccount ensureDemoUser(String username, String displayName, String password) {
        return ensureDemoUser(username, displayName, password, "PROJECT_USER");
    }

    @Transactional
    public UserAccount ensureDemoUser(
            String username,
            String displayName,
            String password,
            String platformRole
    ) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Demo user credentials must not be blank");
        }
        UserAccount user = userAccountService.lambdaQuery()
                .eq(UserAccount::getUsername, username)
                .oneOpt()
                .orElseGet(() -> {
                    UserAccount created = new UserAccount();
                    created.setUsername(username);
                    created.setDisplayName(displayName == null || displayName.isBlank() ? username : displayName);
                    created.setPasswordHash(passwordEncoder.encode(password));
                    created.setEnabled(true);
                    created.setPlatformRole(normalizePlatformRole(platformRole));
                    userAccountService.save(created);
                    return created;
                });
        if (user.getPlatformRole() == null || user.getPlatformRole().isBlank()) {
            user.setPlatformRole(normalizePlatformRole(platformRole));
            userAccountService.updateById(user);
        }
        return user;
    }

    public Duration sessionTtl() {
        return sessionTtl;
    }

    public UserAccount login(String username, String password) {
        return login(username, password, "unknown");
    }

    public UserAccount login(String username, String password, String clientAddress) {
        loginRateLimiter.checkAndRecord(username, clientAddress);
        UserAccount user = userAccountService.lambdaQuery()
                .eq(UserAccount::getUsername, username)
                .oneOpt()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!Boolean.TRUE.equals(user.getEnabled()) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        loginRateLimiter.reset(username, clientAddress);
        return user;
    }

    @Transactional
    public String issueSession(UserAccount user) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        LocalDateTime now = LocalDateTime.now();
        UserSession session = new UserSession();
        session.setTokenHash(hashToken(token));
        session.setUserId(user.getId());
        session.setExpiresAt(now.plus(sessionTtl));
        session.setCreatedAt(now);
        userSessionService.save(session);
        return token;
    }

    @Transactional
    public Long requireSessionUserId(String sessionToken) {
        requireToken(sessionToken);
        LocalDateTime now = LocalDateTime.now();
        UserSession session = userSessionService.lambdaQuery()
                .eq(UserSession::getTokenHash, hashToken(sessionToken))
                .isNull(UserSession::getRevokedAt)
                .gt(UserSession::getExpiresAt, now)
                .oneOpt()
                .orElseThrow(() -> invalidSession());
        boolean enabled = userAccountService.lambdaQuery()
                .eq(UserAccount::getId, session.getUserId())
                .eq(UserAccount::getEnabled, true)
                .exists();
        if (!enabled) {
            session.setRevokedAt(now);
            userSessionService.updateById(session);
            throw invalidSession();
        }
        return session.getUserId();
    }

    @Transactional
    public void revokeSession(String sessionToken) {
        requireToken(sessionToken);
        LocalDateTime now = LocalDateTime.now();
        boolean revoked = userSessionService.lambdaUpdate()
                .eq(UserSession::getTokenHash, hashToken(sessionToken))
                .isNull(UserSession::getRevokedAt)
                .gt(UserSession::getExpiresAt, now)
                .set(UserSession::getRevokedAt, now)
                .update();
        if (!revoked) {
            throw invalidSession();
        }
    }

    private void requireToken(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing session token");
        }
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private ResponseStatusException invalidSession() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session token");
    }

    private String normalizePlatformRole(String platformRole) {
        return platformRole == null || platformRole.isBlank()
                ? "PROJECT_USER"
                : platformRole.trim().toUpperCase();
    }
}
