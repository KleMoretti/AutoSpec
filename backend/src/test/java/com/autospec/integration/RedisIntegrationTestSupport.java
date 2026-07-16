package com.autospec.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
abstract class RedisIntegrationTestSupport {

    private static final int REDIS_PORT = 6379;

    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")
    ).withExposedPorts(REDIS_PORT);

    protected LettuceConnectionFactory redisConnectionFactory;
    protected StringRedisTemplate redisTemplate;

    @BeforeEach
    void connectToRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT)
        );
        redisConnectionFactory = new LettuceConnectionFactory(configuration);
        redisConnectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @AfterEach
    void disconnectFromRedis() {
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }
}
