package com.autospec.service.impl;

import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.WorkflowRunService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

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
}
