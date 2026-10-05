package com.autospec.config;

import com.autospec.service.PromptRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultPromptSeederTest {
    @ParameterizedTest
    @CsvSource({"backend_engineer_loop,backend_engineer,v2", "product_manager_schema,product_manager,v6"})
    void bundledPromptMatchesFrozenContractAcrossLineEndings(String promptKey, String nodeId, String version) throws Exception {
        var registry = mock(PromptRegistryService.class);
        when(registry.activePromptIdOrNull(anyString())).thenReturn(null);
        new DefaultPromptSeeder(registry).run(null);
        var content = ArgumentCaptor.forClass(String.class);
        verify(registry).registerActive(eq(promptKey), eq("v1"), content.capture());
        var spec = new ObjectMapper().readTree(Path.of("..", "agent-engine", "contracts", "archive",
                "autospec-v5-agent-execution-" + version + "-d.workflow.json").toFile());
        String expected = null;
        for (var node : spec.path("nodes")) {
            if (nodeId.equals(node.path("node_id").asText())) {
                expected = node.path("prompt_checksum").asText();
            }
        }
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getValue().getBytes(StandardCharsets.UTF_8)));
        assertThat(checksum).isEqualTo(expected);
        assertThat(content.getValue()).doesNotContain("\r");
    }
}
