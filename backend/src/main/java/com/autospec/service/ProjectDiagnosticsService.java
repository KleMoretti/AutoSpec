package com.autospec.service;

import com.autospec.dto.ProjectDiagnosticsResponse;
import com.autospec.entity.AgentTask;
import com.autospec.entity.AuditEvent;
import com.autospec.entity.CodeGenerationJob;
import com.autospec.entity.ExternalCallLog;
import com.autospec.entity.ModelInvocation;
import com.autospec.entity.WorkflowRun;
import org.springframework.stereotype.Service;

@Service
public class ProjectDiagnosticsService {

    private final WorkflowRunService workflowRunService;
    private final AgentTaskService agentTaskService;
    private final AuditEventService auditEventService;
    private final ExternalCallLogService externalCallLogService;
    private final ModelInvocationService modelInvocationService;
    private final CodeGenerationJobService codeGenerationJobService;

    public ProjectDiagnosticsService(
            WorkflowRunService workflowRunService,
            AgentTaskService agentTaskService,
            AuditEventService auditEventService,
            ExternalCallLogService externalCallLogService,
            ModelInvocationService modelInvocationService,
            CodeGenerationJobService codeGenerationJobService
    ) {
        this.workflowRunService = workflowRunService;
        this.agentTaskService = agentTaskService;
        this.auditEventService = auditEventService;
        this.externalCallLogService = externalCallLogService;
        this.modelInvocationService = modelInvocationService;
        this.codeGenerationJobService = codeGenerationJobService;
    }

    public ProjectDiagnosticsResponse summarize(Long projectId) {
        WorkflowRun latestRun = workflowRunService.lambdaQuery()
                .eq(WorkflowRun::getProjectId, projectId)
                .orderByDesc(WorkflowRun::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        AgentTask latestFailedTask = agentTaskService.lambdaQuery()
                .eq(AgentTask::getProjectId, projectId)
                .eq(AgentTask::getStatus, "FAILED")
                .orderByDesc(AgentTask::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        ExternalCallLog latestFailedExternalCall = externalCallLogService.lambdaQuery()
                .eq(ExternalCallLog::getProjectId, projectId)
                .eq(ExternalCallLog::getStatus, "FAILED")
                .orderByDesc(ExternalCallLog::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        ModelInvocation latestFailedModelInvocation = modelInvocationService.lambdaQuery()
                .eq(ModelInvocation::getProjectId, projectId)
                .eq(ModelInvocation::getStatus, "FAILED")
                .orderByDesc(ModelInvocation::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        CodeGenerationJob latestFailedCodeGenerationJob = codeGenerationJobService.lambdaQuery()
                .eq(CodeGenerationJob::getProjectId, projectId)
                .eq(CodeGenerationJob::getStatus, "FAILED")
                .orderByDesc(CodeGenerationJob::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        return new ProjectDiagnosticsResponse(
                projectId,
                latestRun == null ? null : latestRun.getId(),
                latestRun == null ? null : latestRun.getCorrelationId(),
                workflowRunService.lambdaQuery()
                        .eq(WorkflowRun::getProjectId, projectId)
                        .count(),
                workflowRunService.lambdaQuery()
                        .eq(WorkflowRun::getProjectId, projectId)
                        .eq(WorkflowRun::getStatus, "FAILED")
                        .count(),
                agentTaskService.lambdaQuery()
                        .eq(AgentTask::getProjectId, projectId)
                        .count(),
                agentTaskService.lambdaQuery()
                        .eq(AgentTask::getProjectId, projectId)
                        .eq(AgentTask::getStatus, "FAILED")
                        .count(),
                latestFailedTask == null ? null : latestFailedTask.getNodeName(),
                latestFailedTask == null ? null : latestFailedTask.getErrorMessage(),
                auditEventService.lambdaQuery()
                        .eq(AuditEvent::getProjectId, projectId)
                        .count(),
                externalCallLogService.lambdaQuery()
                        .eq(ExternalCallLog::getProjectId, projectId)
                        .count(),
                externalCallLogService.lambdaQuery()
                        .eq(ExternalCallLog::getProjectId, projectId)
                        .eq(ExternalCallLog::getStatus, "FAILED")
                        .count(),
                latestFailedExternalCall == null ? null : latestFailedExternalCall.getErrorMessage(),
                latestFailedExternalCall == null ? null : latestFailedExternalCall.getDurationMs(),
                modelInvocationService.lambdaQuery()
                        .eq(ModelInvocation::getProjectId, projectId)
                        .count(),
                modelInvocationService.lambdaQuery()
                        .eq(ModelInvocation::getProjectId, projectId)
                        .eq(ModelInvocation::getStatus, "FAILED")
                        .count(),
                latestFailedModelInvocation == null ? null : latestFailedModelInvocation.getAgentNode(),
                latestFailedModelInvocation == null ? null : latestFailedModelInvocation.getModelName(),
                latestFailedModelInvocation == null ? null : latestFailedModelInvocation.getDurationMs(),
                latestFailedModelInvocation == null ? null : latestFailedModelInvocation.getErrorMessage(),
                codeGenerationJobService.lambdaQuery()
                        .eq(CodeGenerationJob::getProjectId, projectId)
                        .count(),
                codeGenerationJobService.lambdaQuery()
                        .eq(CodeGenerationJob::getProjectId, projectId)
                        .eq(CodeGenerationJob::getStatus, "RUNNING")
                        .count(),
                codeGenerationJobService.lambdaQuery()
                        .eq(CodeGenerationJob::getProjectId, projectId)
                        .eq(CodeGenerationJob::getStatus, "FAILED")
                        .count(),
                codeGenerationJobService.lambdaQuery()
                        .eq(CodeGenerationJob::getProjectId, projectId)
                        .eq(CodeGenerationJob::getStatus, "CANCELLED")
                        .count(),
                latestFailedCodeGenerationJob == null ? null : latestFailedCodeGenerationJob.getErrorMessage()
        );
    }
}
