package com.autospec.service;

import com.autospec.entity.UserAccount;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private final UserAccountService userAccountService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    public AuthService(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @Transactional
    public UserAccount ensureDemoOwner() {
        return userAccountService.lambdaQuery()
                .eq(UserAccount::getUsername, "owner")
                .oneOpt()
                .orElseGet(() -> {
                    UserAccount user = new UserAccount();
                    user.setUsername("owner");
                    user.setDisplayName("Owner");
                    user.setPasswordHash(passwordEncoder.encode("owner-pass"));
                    user.setEnabled(true);
                    userAccountService.save(user);
                    return user;
                });
    }

    public UserAccount login(String username, String password) {
        UserAccount user = userAccountService.lambdaQuery()
                .eq(UserAccount::getUsername, username)
                .oneOpt()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!Boolean.TRUE.equals(user.getEnabled()) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return user;
    }

    public String issueSession(UserAccount user) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        sessions.put(token, user.getId());
        return token;
    }

    public Long requireSessionUserId(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing session token");
        }
        Long userId = sessions.get(sessionToken);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session token");
        }
        boolean enabled = userAccountService.lambdaQuery()
                .eq(UserAccount::getId, userId)
                .eq(UserAccount::getEnabled, true)
                .exists();
        if (!enabled) {
            sessions.remove(sessionToken);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session token");
        }
        return userId;
    }
}
