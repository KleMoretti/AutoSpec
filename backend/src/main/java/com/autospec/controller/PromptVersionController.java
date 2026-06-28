package com.autospec.controller;

import com.autospec.dto.PromptVersionResponse;
import com.autospec.entity.PromptVersion;
import com.autospec.service.PromptVersionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/prompts")
public class PromptVersionController {

    private final PromptVersionService promptVersionService;

    public PromptVersionController(PromptVersionService promptVersionService) {
        this.promptVersionService = promptVersionService;
    }

    @GetMapping("/active")
    public List<PromptVersionResponse> activePrompts() {
        return promptVersionService.lambdaQuery()
                .eq(PromptVersion::getActive, true)
                .orderByAsc(PromptVersion::getPromptKey)
                .list()
                .stream()
                .map(PromptVersionResponse::from)
                .toList();
    }
}
