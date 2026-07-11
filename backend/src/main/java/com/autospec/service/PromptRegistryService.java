package com.autospec.service;

import com.autospec.entity.PromptVersion;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class PromptRegistryService {

    private final PromptVersionService promptVersionService;

    public PromptRegistryService(PromptVersionService promptVersionService) {
        this.promptVersionService = promptVersionService;
    }

    @Transactional
    public PromptVersion registerActive(String promptKey, String version, String content) {
        promptVersionService.lambdaUpdate()
                .eq(PromptVersion::getPromptKey, promptKey)
                .set(PromptVersion::getActive, false)
                .update();

        PromptVersion existing = promptVersionService.lambdaQuery()
                .eq(PromptVersion::getPromptKey, promptKey)
                .eq(PromptVersion::getVersion, version)
                .oneOpt()
                .orElse(null);
        PromptVersion prompt = existing == null ? new PromptVersion() : existing;
        prompt.setPromptKey(promptKey);
        prompt.setVersion(version);
        prompt.setContent(content);
        prompt.setChecksum("sha256:" + sha256Hex(content));
        prompt.setActive(true);
        if (prompt.getId() == null) {
            promptVersionService.save(prompt);
        } else {
            promptVersionService.updateById(prompt);
        }
        return prompt;
    }

    public PromptVersion activePrompt(String promptKey) {
        return promptVersionService.lambdaQuery()
                .eq(PromptVersion::getPromptKey, promptKey)
                .eq(PromptVersion::getActive, true)
                .orderByDesc(PromptVersion::getId)
                .last("limit 1")
                .oneOpt()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Active prompt not found"));
    }

    public Long activePromptIdOrNull(String promptKey) {
        return promptVersionService.lambdaQuery()
                .eq(PromptVersion::getPromptKey, promptKey)
                .eq(PromptVersion::getActive, true)
                .orderByDesc(PromptVersion::getId)
                .last("limit 1")
                .oneOpt()
                .map(PromptVersion::getId)
                .orElse(null);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
