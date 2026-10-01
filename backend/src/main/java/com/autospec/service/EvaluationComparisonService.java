package com.autospec.service;

import com.autospec.dto.EvaluationComparisonResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Maps the Agent Engine's versioned evaluation envelope to the public API DTO. */
@Service
public class EvaluationComparisonService {
    private final EvaluationComparisonClient client;
    private final ObjectMapper upstreamMapper;

    public EvaluationComparisonService(EvaluationComparisonClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.upstreamMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    public EvaluationComparisonResponse readOnlyComparison() {
        JsonNode payload;
        try {
            payload = client.fetch();
            return upstreamMapper.treeToValue(payload, EvaluationComparisonResponse.class);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Evaluation comparison evidence is unavailable",
                    exception
            );
        }
    }
}
