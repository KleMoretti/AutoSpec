package com.autospec.service;

import com.autospec.entity.ModelInvocation;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface ModelInvocationService extends IService<ModelInvocation> {

    List<ModelInvocation> listByProjectId(Long projectId, int limit, int offset);

    List<ModelInvocation> listByProjectIdAfterId(Long projectId, Long afterId, int limit);

    List<ModelInvocation> listByProjectAndNodeRunId(
            Long projectId,
            Long workflowNodeRunId,
            int limit,
            int offset
    );

    List<ModelInvocation> listByProjectAndNodeRunIdAfterId(
            Long projectId,
            Long workflowNodeRunId,
            Long afterId,
            int limit
    );
}
