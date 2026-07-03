package com.autospec.service;

import com.autospec.entity.WorkflowRun;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface WorkflowRunService extends IService<WorkflowRun> {

    List<WorkflowRun> listByProjectId(Long projectId);
}
