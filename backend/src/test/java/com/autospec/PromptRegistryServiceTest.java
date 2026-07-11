package com.autospec;

import com.autospec.entity.PromptVersion;
import com.autospec.service.PromptRegistryService;
import com.autospec.service.PromptVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PromptRegistryServiceTest {

    @Autowired
    private PromptRegistryService promptRegistryService;

    @Autowired
    private PromptVersionService promptVersionService;

    @Test
    void registersPromptVersionWithStableChecksumAndSingleActiveVersion() {
        PromptVersion first = promptRegistryService.registerActive(
                "ProductManagerAgent", "v2", "prompt text v2");
        PromptVersion second = promptRegistryService.registerActive(
                "ProductManagerAgent", "v3", "prompt text v3");

        assertThat(first.getChecksum()).startsWith("sha256:");
        assertThat(promptVersionService.getById(first.getId()).getActive()).isFalse();
        assertThat(promptVersionService.getById(second.getId()).getActive()).isTrue();
    }

    @Test
    void returnsActivePromptByKey() {
        promptRegistryService.registerActive("ArchitectAgent", "v1", "architecture prompt");

        PromptVersion active = promptRegistryService.activePrompt("ArchitectAgent");

        assertThat(active.getVersion()).isEqualTo("v1");
        assertThat(active.getContent()).isEqualTo("architecture prompt");
    }

    @Test
    void activePromptLookupUsesLatestVersionWhenDirtyDataHasMultipleActiveRows() {
        PromptVersion older = prompt("DuplicatePromptAgent", "v1", "older prompt");
        PromptVersion newer = prompt("DuplicatePromptAgent", "v2", "newer prompt");
        promptVersionService.save(older);
        promptVersionService.save(newer);

        PromptVersion active = promptRegistryService.activePrompt("DuplicatePromptAgent");

        assertThat(active.getId()).isEqualTo(newer.getId());
        assertThat(promptRegistryService.activePromptIdOrNull("DuplicatePromptAgent")).isEqualTo(newer.getId());
    }

    private PromptVersion prompt(String promptKey, String version, String content) {
        PromptVersion prompt = new PromptVersion();
        prompt.setPromptKey(promptKey);
        prompt.setVersion(version);
        prompt.setContent(content);
        prompt.setChecksum("sha256:" + version);
        prompt.setActive(true);
        return prompt;
    }
}
