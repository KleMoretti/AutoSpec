package com.autospec.config;

import com.autospec.service.AuthService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "autospec.auth.demo-user", name = "enabled", havingValue = "true")
public class DemoUserInitializer implements InitializingBean {

    private final AuthService authService;
    private final String username;
    private final String displayName;
    private final String password;
    private final String platformRole;

    public DemoUserInitializer(
            AuthService authService,
            @Value("${autospec.auth.demo-user.username:owner}") String username,
            @Value("${autospec.auth.demo-user.display-name:Owner}") String displayName,
            @Value("${autospec.auth.demo-user.password:}") String password,
            @Value("${autospec.auth.demo-user.platform-role:PLATFORM_ADMIN}") String platformRole
    ) {
        this.authService = authService;
        this.username = username;
        this.displayName = displayName;
        this.password = password;
        this.platformRole = platformRole;
    }

    @Override
    public void afterPropertiesSet() {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "autospec.auth.demo-user.password is required when the demo user is enabled"
            );
        }
        authService.ensureDemoUser(username, displayName, password, platformRole);
    }
}
