package com.autospec.mapper;

import com.autospec.dto.WorkflowOutboxBacklogSnapshot;
import com.autospec.entity.WorkflowOutbox;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WorkflowOutboxMapper extends BaseMapper<WorkflowOutbox> {
    @Select("""
            SELECT COUNT(*) AS pending_count, MIN(created_at) AS oldest_created_at
            FROM workflow_outbox
            WHERE status = 'PENDING'
            """)
    @Results({
            @Result(column = "pending_count", property = "pendingCount"),
            @Result(column = "oldest_created_at", property = "oldestCreatedAt")
    })
    WorkflowOutboxBacklogSnapshot selectPendingBacklog();
}
