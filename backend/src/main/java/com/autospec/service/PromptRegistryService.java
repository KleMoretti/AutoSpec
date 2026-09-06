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
        String canonicalKey = normalizePromptKey(promptKey);
        String expectedChecksum = "sha256:" + sha256Hex(content);
        PromptVersion existing = promptVersionService.lambdaQuery()
                .eq(PromptVersion::getPromptKey, canonicalKey)
                .eq(PromptVersion::getVersion, version)
                .oneOpt()
                .orElse(null);
        if (existing != null
                && (!java.util.Objects.equals(existing.getContent(), content)
                || !expectedChecksum.equals(existing.getChecksum()))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Prompt version is immutable and its content/checksum already exists"
            );
        }
        promptVersionService.lambdaUpdate()
                .eq(PromptVersion::getPromptKey, canonicalKey)
                .set(PromptVersion::getActive, false)
                .update();

        PromptVersion prompt = existing == null ? new PromptVersion() : existing;
        prompt.setPromptKey(canonicalKey);
        prompt.setVersion(version);
        prompt.setContent(content);
        prompt.setChecksum(expectedChecksum);
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
                .eq(PromptVersion::getPromptKey, normalizePromptKey(promptKey))
                .eq(PromptVersion::getActive, true)
                .orderByDesc(PromptVersion::getId)
                .last("limit 1")
                .oneOpt()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Active prompt not found"));
    }

    public Long activePromptIdOrNull(String promptKey) {
        return promptVersionService.lambdaQuery()
                .eq(PromptVersion::getPromptKey, normalizePromptKey(promptKey))
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

    public String normalizePromptKey(String promptKey) {
        if (promptKey == null || promptKey.isBlank()) {
            throw new IllegalArgumentException("promptKey is required");
        }
        return switch (promptKey.trim()) {
            case "ProductManagerAgent" -> "product_manager";
            case "ArchitectAgent" -> "architect";
            case "BackendEngineerAgent" -> "backend_engineer";
            case "FrontendEngineerAgent" -> "frontend_engineer";
            case "ReviewerAgent" -> "reviewer";
            case "EvaluatorAgent" -> "evaluator";
            default -> promptKey.trim();
        };
    }
}
