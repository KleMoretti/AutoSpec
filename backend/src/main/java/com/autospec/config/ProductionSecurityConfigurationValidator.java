package com.autospec.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ProductionSecurityConfigurationValidator implements InitializingBean {

    private final String environment;
    private final boolean demoUserEnabled;
    private final boolean secureCookie;
    private final String sameSite;
    private final boolean exposeSessionToken;
    private final String agentEngineServiceToken;
    private final String datasourceUrl;
    private final String datasourceUsername;
    private final String datasourcePassword;
    private final String redisPassword;
    private final boolean redisSslEnabled;

    public ProductionSecurityConfigurationValidator(
            @Value("${autospec.environment:development}") String environment,
            @Value("${autospec.auth.demo-user.enabled:false}") boolean demoUserEnabled,
            @Value("${autospec.auth.session.cookie-secure:false}") boolean secureCookie,
            @Value("${autospec.auth.session.same-site:Strict}") String sameSite,
            @Value("${autospec.auth.session.expose-token-in-response:false}") boolean exposeSessionToken,
            @Value("${autospec.agent-engine.service-token:}") String agentEngineServiceToken,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${spring.datasource.username:}") String datasourceUsername,
            @Value("${spring.datasource.password:}") String datasourcePassword,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${spring.data.redis.ssl.enabled:false}") boolean redisSslEnabled
    ) {
        this.environment = environment;
        this.demoUserEnabled = demoUserEnabled;
        this.secureCookie = secureCookie;
        this.sameSite = sameSite;
        this.exposeSessionToken = exposeSessionToken;
        this.agentEngineServiceToken = agentEngineServiceToken;
        this.datasourceUrl = datasourceUrl;
        this.datasourceUsername = datasourceUsername;
        this.datasourcePassword = datasourcePassword;
        this.redisPassword = redisPassword;
        this.redisSslEnabled = redisSslEnabled;
    }

    @Override
    public void afterPropertiesSet() {
        if (!List.of("production", "prod").contains(environment.trim().toLowerCase())) {
            return;
        }
        List<String> violations = new ArrayList<>();
        if (demoUserEnabled) violations.add("demo user must be disabled");
        if (!secureCookie) violations.add("session cookies must be Secure");
        if (!"strict".equals(sameSite == null ? "" : sameSite.trim().toLowerCase(Locale.ROOT))) {
            violations.add("session cookies must use SameSite=Strict");
        }
        if (exposeSessionToken) violations.add("session tokens must not be exposed in login responses");
        if (agentEngineServiceToken == null || agentEngineServiceToken.isBlank()) {
            violations.add("Agent Engine service token is required");
        }
        if (datasourceUsername == null || datasourceUsername.isBlank()
                || "root".equals(datasourceUsername.trim().toLowerCase(Locale.ROOT))) {
            violations.add("database application user must be non-root");
        }
        if (datasourcePassword == null || datasourcePassword.isBlank()) {
            violations.add("database password is required");
        }
        if (redisPassword == null || redisPassword.isBlank()) {
            violations.add("Redis password is required");
        }
        if (!redisSslEnabled) {
            violations.add("Redis TLS must be enabled");
        }
        String normalizedDatasourceUrl = datasourceUrl == null
                ? ""
                : datasourceUrl.toLowerCase(Locale.ROOT);
        boolean databaseTlsEnabled = normalizedDatasourceUrl.contains("usessl=true")
                || normalizedDatasourceUrl.contains("sslmode=required")
                || normalizedDatasourceUrl.contains("sslmode=verify_ca")
                || normalizedDatasourceUrl.contains("sslmode=verify_identity");
        if (!databaseTlsEnabled) {
            violations.add("database TLS must be enabled");
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe production configuration: " + String.join("; ", violations)
            );
        }
    }
}
