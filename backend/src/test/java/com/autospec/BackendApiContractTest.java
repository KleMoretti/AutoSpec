package com.autospec;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BackendApiContractTest {

    @Test
    void packagedOpenApiContractDocumentsCoreBackendEndpointsAndErrorModel() throws Exception {
        InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("contracts/autospec-backend-v1.openapi.yaml");

        assertThat(stream).isNotNull();
        String contract = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

        assertThat(contract)
                .contains("openapi: 3.0.3")
                .contains("/api/contracts/openapi")
                .contains("/api/projects")
                .contains("/api/projects/{projectId}/diagnostics")
                .contains("/api/projects/{projectId}/generate-v4")
                .contains("/api/projects/{projectId}/exports")
                .contains("/api/projects/{projectId}/exports/{exportFileId}")
                .contains("ApiErrorResponse")
                .contains("agentTaskCount")
                .contains("failedAgentTaskCount")
                .contains("latestFailedAgentTaskNodeName")
                .contains("latestFailedAgentTaskErrorMessage")
                .contains("latestFailedExternalCallErrorMessage")
                .contains("latestFailedExternalCallDurationMs")
                .contains("failedModelInvocationCount")
                .contains("latestFailedModelInvocationAgentNode")
                .contains("latestFailedModelInvocationModelName")
                .contains("latestFailedModelInvocationDurationMs")
                .contains("latestFailedModelInvocationErrorMessage")
                .contains("PaginationLimit")
                .contains("PaginationOffset")
                .contains("X-AutoSpec-Session-Token")
                .contains("Idempotency-Key");
    }
}
