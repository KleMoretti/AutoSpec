package com.autospec.controller;

import com.autospec.dto.PromptVersionResponse;
import com.autospec.entity.PromptVersion;
import com.autospec.service.AuthService;
import com.autospec.service.PromptVersionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/prompts")
public class PromptVersionController {

    private final PromptVersionService promptVersionService;
    private final AuthService authService;

    public PromptVersionController(
            PromptVersionService promptVersionService,
            AuthService authService
    ) {
        this.promptVersionService = promptVersionService;
        this.authService = authService;
    }

    @GetMapping("/active")
    public List<PromptVersionResponse> activePrompts(
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "X-AutoSpec-Session-Token", required = false
            ) String sessionToken
    ) {
        authService.requireSessionUserId(sessionToken);
        return promptVersionService.lambdaQuery()
                .eq(PromptVersion::getActive, true)
                .orderByAsc(PromptVersion::getPromptKey)
                .list()
                .stream()
                .map(PromptVersionResponse::from)
                .toList();
    }
}
