package com.autospec.workflow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestrictedConditionEvaluatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestrictedConditionEvaluator evaluator = new RestrictedConditionEvaluator();
    private final JsonNode payload = json("""
            {
              "decision":"REWORK",
              "score":72,
              "route":{"owner":"backend_engineer"},
              "tags":["API","DATABASE"]
            }
            """);

    @Test
    void supportsDeclaredComparisonOperators() {
        assertThat(evaluate("{\"path\":\"$.decision\",\"operator\":\"EQ\",\"value\":\"REWORK\"}"))
                .isTrue();
        assertThat(evaluate("{\"path\":\"$.score\",\"operator\":\"NE\",\"value\":100}"))
                .isTrue();
        assertThat(evaluate("{\"path\":\"$.route.owner\",\"operator\":\"IN\",\"value\":[\"backend_engineer\",\"architect\"]}"))
                .isTrue();
        assertThat(evaluate("{\"path\":\"$.route.owner\",\"operator\":\"EXISTS\"}"))
                .isTrue();
        assertThat(evaluate("{\"path\":\"$.route.missing\",\"operator\":\"EXISTS\"}"))
                .isFalse();
    }

    @Test
    void supportsNestedBooleanAllAndAny() {
        assertThat(evaluate("""
                {
                  "all":[
                    {"path":"$.decision","operator":"EQ","value":"REWORK"},
                    {"any":[
                      {"path":"$.score","operator":"EQ","value":100},
                      {"path":"$.route.owner","operator":"EQ","value":"backend_engineer"}
                    ]}
                  ]
                }
                """)).isTrue();
    }

    @Test
    void rejectsInvalidJsonPathsAndUnsupportedOperators() {
        assertThatThrownBy(() -> evaluate("""
                {"path":"$['decision']","operator":"EQ","value":"REWORK"}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
        assertThatThrownBy(() -> evaluate("""
                {"path":"$.score","operator":"GT","value":60}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator");
        assertThatThrownBy(() -> evaluate("""
                {"script":"payload.score > 60"}
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresInValueToBeAnArrayAndBooleanGroupsToBeNonEmpty() {
        assertThatThrownBy(() -> evaluate("""
                {"path":"$.decision","operator":"IN","value":"REWORK"}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("array");
        assertThatThrownBy(() -> evaluate("{\"all\":[]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all");
    }

    private boolean evaluate(String condition) {
        return evaluator.evaluate(payload, json(condition));
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
