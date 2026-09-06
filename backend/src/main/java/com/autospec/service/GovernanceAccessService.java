package com.autospec.service;

import com.autospec.entity.UserAccount;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Platform-level authorization for global workflow, prompt, and model governance. */
@Service
public class GovernanceAccessService {
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    private final AuthService authService;
    private final UserAccountService userAccountService;

    public GovernanceAccessService(
            AuthService authService,
            UserAccountService userAccountService
    ) {
        this.authService = authService;
        this.userAccountService = userAccountService;
    }

    public Long requirePlatformAdmin(String sessionToken) {
        Long userId = authService.requireSessionUserId(sessionToken);
        UserAccount user = userAccountService.getById(userId);
        if (user == null || !PLATFORM_ADMIN.equals(user.getPlatformRole())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Platform governance capability is required"
            );
        }
        return userId;
    }
}
