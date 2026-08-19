package com.autospec.service.impl;

import com.autospec.entity.WorkflowRun;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.WorkflowRunService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkflowRunServiceImpl extends ServiceImpl<WorkflowRunMapper, WorkflowRun> implements WorkflowRunService {

    private static final List<String> TERMINAL_NODE_STATUSES = List.of(
            "SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"
    );

    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowOutboxMapper outboxMapper;

    public WorkflowRunServiceImpl(WorkflowNodeRunMapper nodeRunMapper, WorkflowOutboxMapper outboxMapper) {
        this.nodeRunMapper = nodeRunMapper;
        this.outboxMapper = outboxMapper;
    }

    @Override
    public List<WorkflowRun> listByProjectId(Long projectId) {
        return lambdaQuery()
                .eq(WorkflowRun::getProjectId, projectId)
                .orderByAsc(WorkflowRun::getId)
                .list();
    }

    @Override
    public List<WorkflowRun> listByProjectId(Long projectId, int limit, int offset) {
        return lambdaQuery()
                .eq(WorkflowRun::getProjectId, projectId)
                .orderByAsc(WorkflowRun::getId)
                .last("limit " + limit + " offset " + offset)
                .list();
    }

    @Override
    public List<WorkflowRun> listByProjectIdAfterId(Long projectId, Long afterId, int limit) {
        LambdaQueryChainWrapper<WorkflowRun> query = lambdaQuery()
                .eq(WorkflowRun::getProjectId, projectId);
        if (afterId != null) {
            query.gt(WorkflowRun::getId, afterId);
        }
        return query.orderByAsc(WorkflowRun::getId)
                .last("limit " + limit)
                .list();
    }

    @Override
    @Transactional
    public WorkflowRun cancelRunningRun(Long projectId, Long runId) {
        WorkflowRun run = getById(runId);
        if (run == null || !projectId.equals(run.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow run not found");
        }
        if (!"RUNNING".equals(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only running workflow runs can be cancelled");
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<WorkflowRun> runUpdate = new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, runId)
                .eq(WorkflowRun::getStatus, "RUNNING")
                .set(WorkflowRun::getStatus, "CANCELLED")
                .set(WorkflowRun::getResponseStatus, "CANCELLED")
                .set(WorkflowRun::getErrorMessage, "Cancelled by user request")
                .set(WorkflowRun::getCompletedAt, now)
                .set(WorkflowRun::getUpdatedAt, now);
        if (run.getLockVersion() == null) {
            runUpdate.isNull(WorkflowRun::getLockVersion).set(WorkflowRun::getLockVersion, 1);
        } else {
            runUpdate.eq(WorkflowRun::getLockVersion, run.getLockVersion())
                    .set(WorkflowRun::getLockVersion, run.getLockVersion() + 1);
        }
        if (baseMapper.update(null, runUpdate) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow run changed while it was being cancelled");
        }

        nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, runId)
                .notIn(WorkflowNodeRun::getStatus, TERMINAL_NODE_STATUSES)
                .set(WorkflowNodeRun::getStatus, "CANCELLED")
                .set(WorkflowNodeRun::getErrorCode, "RUN_CANCELLED")
                .set(WorkflowNodeRun::getErrorMessage, "Parent workflow run was cancelled")
                .set(WorkflowNodeRun::getFinishedAt, now)
                .set(WorkflowNodeRun::getUpdatedAt, now));

        outboxMapper.update(null, new LambdaUpdateWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getAggregateId, Long.toString(runId))
                .eq(WorkflowOutbox::getStatus, "PENDING")
                .set(WorkflowOutbox::getStatus, "CLOSED")
                .set(WorkflowOutbox::getClosedAt, now)
                .set(WorkflowOutbox::getUpdatedAt, now));

        run.setStatus("CANCELLED");
        run.setResponseStatus("CANCELLED");
        run.setErrorMessage("Cancelled by user request");
        run.setCompletedAt(now);
        run.setUpdatedAt(now);
        run.setLockVersion(run.getLockVersion() == null ? 1 : run.getLockVersion() + 1);
        return run;
    }

    @Override
    @Transactional
    public int timeoutRunningRunsBefore(LocalDateTime cutoff) {
        List<WorkflowRun> staleRuns = lambdaQuery()
                .eq(WorkflowRun::getStatus, "RUNNING")
                .lt(WorkflowRun::getCreatedAt, cutoff)
                .list();
        LocalDateTime now = LocalDateTime.now();
        for (WorkflowRun run : staleRuns) {
            run.setStatus("FAILED");
            run.setErrorMessage("Timed out while running workflow run");
            run.setCompletedAt(now);
            updateById(run);
        }
        return staleRuns.size();
    }
}
