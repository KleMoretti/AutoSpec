package com.autospec;

import com.autospec.controller.EvaluationComparisonController;
import com.autospec.dto.EvaluationComparisonResponse;
import com.autospec.service.EvaluationComparisonService;
import com.autospec.service.GovernanceAccessService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationComparisonControllerTest {
    @Test
    void evaluationComparisonRequiresPlatformGovernanceAndIsReadOnly() {
        GovernanceAccessService governanceAccessService = mock(GovernanceAccessService.class);
        EvaluationComparisonService comparisonService = mock(EvaluationComparisonService.class);
        EvaluationComparisonResponse expected = new EvaluationComparisonResponse(
                "NOT_EVALUATED",
                "NONE",
                new EvaluationComparisonResponse.EvaluationMatrixResponse(
                        "not-executed", "eval-v1", null, null, 1L, List.of()
                ),
                new EvaluationComparisonResponse.EvaluationGateDecisionResponse(
                        "NOT_EVALUATED", "NOT_EVALUATED", List.of("missing"), "a", "d", null, null
                ),
                "missing"
        );
        when(comparisonService.readOnlyComparison()).thenReturn(expected);

        EvaluationComparisonController controller = new EvaluationComparisonController(
                governanceAccessService,
                comparisonService
        );

        assertThat(controller.ablation("session")).isSameAs(expected);
        verify(governanceAccessService).requirePlatformAdmin("session");
        verify(comparisonService).readOnlyComparison();
    }
}
