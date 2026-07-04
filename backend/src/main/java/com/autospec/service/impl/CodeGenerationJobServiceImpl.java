package com.autospec.service.impl;

import com.autospec.entity.CodeGenerationJob;
import com.autospec.mapper.CodeGenerationJobMapper;
import com.autospec.service.CodeGenerationJobService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CodeGenerationJobServiceImpl extends ServiceImpl<CodeGenerationJobMapper, CodeGenerationJob> implements CodeGenerationJobService {

    @Override
    public List<CodeGenerationJob> listByProjectId(Long projectId, int limit, int offset) {
        return lambdaQuery()
                .eq(CodeGenerationJob::getProjectId, projectId)
                .orderByAsc(CodeGenerationJob::getId)
                .last("limit " + limit + " offset " + offset)
                .list();
    }

    @Override
    @Transactional
    public CodeGenerationJob cancelRunningJob(Long projectId, Long jobId) {
        CodeGenerationJob job = getById(jobId);
        if (job == null || !projectId.equals(job.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Code generation job not found");
        }
        if (!"RUNNING".equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only running code generation jobs can be cancelled");
        }
        LocalDateTime now = LocalDateTime.now();
        job.setStatus("CANCELLED");
        job.setErrorMessage("Cancelled by user");
        job.setCompletedAt(now);
        job.setCancelledAt(now);
        updateById(job);
        return job;
    }
}
