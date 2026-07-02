package com.autospec.service;

import com.autospec.dto.WorkflowSnapshotResponse;
import com.autospec.entity.WorkflowSnapshot;
import com.baomidou.mybatisplus.extension.service.IService;

public interface WorkflowSnapshotService extends IService<WorkflowSnapshot> {

    void ensureDefaultSnapshot(Long projectId);

    WorkflowSnapshotResponse latestResponse(Long projectId);
}
