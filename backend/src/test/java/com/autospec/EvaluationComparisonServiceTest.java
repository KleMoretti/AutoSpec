package com.autospec;

import com.autospec.dto.EvaluationComparisonResponse;
import com.autospec.service.EvaluationComparisonClient;
import com.autospec.service.EvaluationComparisonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationComparisonServiceTest {
    @Test
    void mapsVersionedSnakeCaseAgentEvidenceToPublicCamelCaseDto() throws Exception {
        EvaluationComparisonClient client = mock(EvaluationComparisonClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(client.fetch()).thenReturn(objectMapper.readTree("""
                {
                  "status": "NOT_EVALUATED",
                  "source": "NONE",
                  "matrix": {
                    "matrix_id": "not-executed",
                    "dataset_version": "eval-v1",
                    "generated_at_epoch_ms": 1700000000000,
                    "runs": []
                  },
                  "decision": {
                    "decision": "NOT_EVALUATED",
                    "gate_status": "NOT_EVALUATED",
                    "reasons": ["no published evidence"],
                    "baseline_run_id": "baseline",
                    "candidate_run_id": "candidate"
                  },
                  "not_evaluated_reason": "no published evidence"
                }
                """));

        EvaluationComparisonResponse response = new EvaluationComparisonService(client, objectMapper)
                .readOnlyComparison();

        assertThat(response.status()).isEqualTo("NOT_EVALUATED");
        assertThat(response.matrix().matrixId()).isEqualTo("not-executed");
        assertThat(response.matrix().generatedAtEpochMs()).isEqualTo(1700000000000L);
        assertThat(response.decision().baselineRunId()).isEqualTo("baseline");
        assertThat(response.notEvaluatedReason()).isEqualTo("no published evidence");
    }
}
