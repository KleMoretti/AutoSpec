package com.autospec.mapper;

import com.autospec.entity.WorkflowEventDeadLetter;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowEventDeadLetterMapper extends BaseMapper<WorkflowEventDeadLetter> {
    @Insert("""
            INSERT IGNORE INTO workflow_event_dead_letter
                (stream_message_id, payload_json, error_type, error_message, status, created_at)
            VALUES
                (#{streamMessageId}, #{payloadJson}, #{errorType}, #{errorMessage}, #{status}, #{createdAt})
            """)
    int insertIfAbsent(WorkflowEventDeadLetter value);
}
