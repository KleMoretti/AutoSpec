package com.autospec.service.impl;

import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.WorkflowRunService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkflowRunServiceImpl extends ServiceImpl<WorkflowRunMapper, WorkflowRun> implements WorkflowRunService {

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
    @Transactional
    public WorkflowRun cancelRunningRun(Long projectId, Long runId) {
        WorkflowRun run = getById(runId);
        if (run == null || !projectId.equals(run.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow run not found");
        }
        if (!"RUNNING".equals(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only running workflow runs can be cancelled");
        }
        run.setStatus("CANCELLED");
        run.setErrorMessage("Cancelled by user request");
        run.setCompletedAt(LocalDateTime.now());
        updateById(run);
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
