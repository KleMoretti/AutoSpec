package com.autospec.mapper;

import com.autospec.entity.ProcessedWorkflowEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProcessedWorkflowEventMapper extends BaseMapper<ProcessedWorkflowEvent> {
    @Insert("""
            INSERT IGNORE INTO processed_workflow_event (event_id, event_type, processed_at)
            VALUES (#{eventId}, #{eventType}, #{processedAt})
            """)
    int insertIfAbsent(ProcessedWorkflowEvent event);
}
