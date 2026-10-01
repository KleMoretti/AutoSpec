package com.autospec.mapper;

import com.autospec.entity.WorkflowEventDeliveryAttempt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface WorkflowEventDeliveryAttemptMapper extends BaseMapper<WorkflowEventDeliveryAttempt> {
    @Insert("""
            INSERT INTO workflow_event_delivery_attempt
                (stream_message_id, consumer_group, delivery_count, last_attempt_at)
            VALUES (#{messageId}, #{consumerGroup}, 1, #{now})
            ON DUPLICATE KEY UPDATE
                delivery_count = delivery_count + 1,
                last_attempt_at = #{now}
            """)
    int recordAttempt(String messageId, String consumerGroup, LocalDateTime now);

    @Select("""
            SELECT delivery_count
            FROM workflow_event_delivery_attempt
            WHERE stream_message_id = #{messageId} AND consumer_group = #{consumerGroup}
            """)
    Integer selectDeliveryCount(String messageId, String consumerGroup);
}
