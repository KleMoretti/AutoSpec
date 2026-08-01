package com.autospec.integration;

import com.autospec.entity.Project;
import com.autospec.service.ProjectService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.validation-timeout=500",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0"
})
@ActiveProfiles("integration-test")
@Testcontainers
class MySqlFailureRecoveryIT {
    private static final Logger LOGGER = LoggerFactory.getLogger(MySqlFailureRecoveryIT.class);
    private static final int MYSQL_PORT = 3306;
    private static final Duration FAILURE_DETECTION_RTO = Duration.ofSeconds(5);
    private static final Duration RECOVERY_RTO = Duration.ofSeconds(10);
    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.4")
    )
            .withDatabaseName("autospec_failure_drill")
            .withUsername("autospec")
            .withPassword("autospec")
            .withNetwork(NETWORK)
            .withNetworkAliases("mysql");

    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0")
    ).withNetwork(NETWORK);

    private static ToxiproxyContainer.ContainerProxy mysqlProxy;

    @Autowired
    private ProjectService projectService;

    @DynamicPropertySource
    static void configureMySql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MySqlFailureRecoveryIT::proxiedJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @BeforeEach
    void restoreBeforeDrill() {
        proxy().setConnectionCut(false);
    }

    @AfterEach
    void restoreAfterDrill() {
        proxy().setConnectionCut(false);
    }

    @AfterAll
    static void closeNetwork() {
        NETWORK.close();
    }

    @Test
    void disconnectedMySqlFailsFastWithoutPartialWriteAndRecoversWithoutRepair()
            throws Exception {
        Project anchor = project("mysql-anchor-");
        projectService.save(anchor);
        Project interruptedWrite = project("mysql-interrupted-");

        proxy().setConnectionCut(true);
        Instant failureAt = Instant.now();
        assertThatThrownBy(() -> projectService.save(interruptedWrite))
                .isInstanceOf(RuntimeException.class);
        long detectionMillis = Duration.between(failureAt, Instant.now()).toMillis();

        proxy().setConnectionCut(false);
        Instant restoredAt = Instant.now();
        Project recoveredAnchor = awaitReadable(anchor.getId(), restoredAt);
        long recoveryMillis = Duration.between(restoredAt, Instant.now()).toMillis();
        long partialWrites = projectService.count(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getName, interruptedWrite.getName())
        );
        Project postRecoveryWrite = project("mysql-recovered-");
        projectService.save(postRecoveryWrite);

        LOGGER.info(
                "failureDrill=mysql-disconnect detectionMs={} recoveryMs={} "
                        + "partialWrites={} manualRepairs=0 finalReadable={} postRecoveryWriteId={}",
                detectionMillis,
                recoveryMillis,
                partialWrites,
                recoveredAnchor != null,
                postRecoveryWrite.getId()
        );

        assertThat(detectionMillis).isLessThan(FAILURE_DETECTION_RTO.toMillis());
        assertThat(recoveryMillis).isLessThan(RECOVERY_RTO.toMillis());
        assertThat(recoveredAnchor.getName()).isEqualTo(anchor.getName());
        assertThat(partialWrites).isZero();
        assertThat(postRecoveryWrite.getId()).isPositive();
    }

    private Project awaitReadable(long projectId, Instant restoredAt) throws Exception {
        RuntimeException lastFailure = null;
        while (Duration.between(restoredAt, Instant.now()).compareTo(RECOVERY_RTO) < 0) {
            try {
                Project project = projectService.getById(projectId);
                if (project != null) {
                    return project;
                }
            } catch (RuntimeException failure) {
                lastFailure = failure;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("MySQL did not recover within RTO", lastFailure);
    }

    private Project project(String namePrefix) {
        Project project = new Project();
        project.setUserId(0L);
        project.setName(namePrefix + UUID.randomUUID());
        project.setOriginalRequirement("Verify MySQL failure recovery without partial state.");
        project.setStatus("CREATED");
        return project;
    }

    private static String proxiedJdbcUrl() {
        ToxiproxyContainer.ContainerProxy proxy = proxy();
        return "jdbc:mysql://" + proxy.getContainerIpAddress()
                + ":" + proxy.getProxyPort()
                + "/" + MYSQL.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&connectTimeout=500&socketTimeout=500";
    }

    @SuppressWarnings("deprecation")
    private static synchronized ToxiproxyContainer.ContainerProxy proxy() {
        if (mysqlProxy == null) {
            mysqlProxy = TOXIPROXY.getProxy(MYSQL, MYSQL_PORT);
        }
        return mysqlProxy;
    }
}
