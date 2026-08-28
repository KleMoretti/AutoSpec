package com.autospec.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityConfigurationValidatorTest {

    @Test
    void developmentConfigurationDoesNotRequireProductionSecrets() {
        ProductionSecurityConfigurationValidator validator = validator(
                "development", true, false, "None", true, "jdbc:mysql://localhost/autospec?useSSL=false", "root", "", "", false
        );

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void productionFailsClosedWhenAnySecurityBoundaryIsUnsafe() {
        ProductionSecurityConfigurationValidator validator = validator(
                "production", true, false, "None", true, "jdbc:mysql://db/autospec?useSSL=false", "root", "", "", false
        );

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo user must be disabled")
                .hasMessageContaining("session cookies must be Secure")
                .hasMessageContaining("session cookies must use SameSite=Strict")
                .hasMessageContaining("session tokens must not be exposed")
                .hasMessageContaining("database application user must be non-root")
                .hasMessageContaining("database password is required")
                .hasMessageContaining("Redis password is required")
                .hasMessageContaining("Redis TLS must be enabled")
                .hasMessageContaining("database TLS must be enabled");
    }

    @Test
    void productionAcceptsExplicitTlsAndNonBlankSecrets() {
        ProductionSecurityConfigurationValidator validator = validator(
                "prod",
                false,
                true,
                "Strict",
                false,
                "jdbc:mysql://db/autospec?sslMode=VERIFY_IDENTITY",
                "autospec",
                "database-password",
                "redis-password",
                true
        );

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    private ProductionSecurityConfigurationValidator validator(
            String environment,
            boolean demoUserEnabled,
            boolean secureCookie,
            String sameSite,
            boolean exposeSessionToken,
            String datasourceUrl,
            String datasourceUsername,
            String datasourcePassword,
            String redisPassword,
            boolean redisSslEnabled
    ) {
        return new ProductionSecurityConfigurationValidator(
                environment,
                demoUserEnabled,
                secureCookie,
                sameSite,
                exposeSessionToken,
                datasourceUrl,
                datasourceUsername,
                datasourcePassword,
                redisPassword,
                redisSslEnabled
        );
    }
}
