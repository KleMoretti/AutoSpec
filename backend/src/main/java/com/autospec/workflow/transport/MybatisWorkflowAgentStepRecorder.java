package com.autospec.workflow.transport;

import com.autospec.entity.WorkflowAgentStepFact;
import com.autospec.mapper.WorkflowAgentStepFactMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Persists compact loop telemetry while keeping the event consumer idempotent. */
@Component
public class MybatisWorkflowAgentStepRecorder implements WorkflowAgentStepRecorder {
    private final WorkflowAgentStepFactMapper stepMapper;
    private final ObjectMapper objectMapper;

    @Autowired
    public MybatisWorkflowAgentStepRecorder(
            WorkflowAgentStepFactMapper stepMapper,
            ObjectMapper objectMapper
    ) {
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
    }

    public MybatisWorkflowAgentStepRecorder(WorkflowAgentStepFactMapper stepMapper) {
        this(stepMapper, new ObjectMapper());
    }

    @Override
    @Transactional
    public void record(WorkflowExecutionEvent event) {
        if (!event.isTerminal() || event.agentSteps().isEmpty()) {
            return;
        }
        for (WorkflowAgentStep step : event.agentSteps()) {
            WorkflowAgentStepFact existingFact = stepMapper.selectOne(
                    new LambdaQueryWrapper<WorkflowAgentStepFact>()
                    .select(WorkflowAgentStepFact::getId)
                    .eq(WorkflowAgentStepFact::getExecutionId, event.executionId())
                    .eq(WorkflowAgentStepFact::getStep, step.step())
            );
            if (existingFact != null) {
                continue;
            }
            WorkflowAgentStepFact fact = new WorkflowAgentStepFact();
            fact.setWorkflowRunId(event.workflowRunId());
            fact.setNodeRunId(event.nodeRunId());
            fact.setNodeId(event.nodeId());
            fact.setRevision(event.revision());
            fact.setAttempt(event.attempt());
            fact.setExecutionId(event.executionId());
            fact.setContractHash(event.contractHash());
            fact.setStep(step.step());
            fact.setPhase(step.phase());
            fact.setStatus(step.status());
            fact.setReasonCode(step.reasonCode());
            fact.setPlanHash(step.planHash());
            fact.setObservationHash(step.observationHash());
            fact.setValidationIssueCodesJson(serialize(step.validationIssueCodes()));
            fact.setModelCallRef(step.modelCallRef());
            fact.setToolCallRef(step.toolCallRef());
            fact.setStartedAtEpochMs(step.startedAtEpochMs());
            fact.setFinishedAtEpochMs(step.finishedAtEpochMs());
            fact.setDurationMs(step.durationMs());
            fact.setCreatedAt(LocalDateTime.now());
            // The unique execution_id/step key is the final guard for a
            // concurrent replay that races this read-before-insert check.
            stepMapper.insert(fact);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize agent step validation codes",
                    exception
            );
        }
    }
}
