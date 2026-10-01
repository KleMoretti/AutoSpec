package com.autospec.controller;

import com.autospec.dto.EvaluationComparisonResponse;
import com.autospec.service.EvaluationComparisonService;
import com.autospec.service.GovernanceAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-admin-only, read-only evaluation evidence endpoint. */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationComparisonController {
    private final GovernanceAccessService governanceAccessService;
    private final EvaluationComparisonService evaluationComparisonService;

    public EvaluationComparisonController(
            GovernanceAccessService governanceAccessService,
            EvaluationComparisonService evaluationComparisonService
    ) {
        this.governanceAccessService = governanceAccessService;
        this.evaluationComparisonService = evaluationComparisonService;
    }

    @GetMapping("/ablation")
    public EvaluationComparisonResponse ablation(
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        governanceAccessService.requirePlatformAdmin(sessionToken);
        return evaluationComparisonService.readOnlyComparison();
    }
}
