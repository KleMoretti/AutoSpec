package com.autospec.mapper;

import com.autospec.entity.WorkflowRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {
    @Update("""
            update workflow_run
               set consumed_tokens = consumed_tokens + #{tokens},
                   consumed_cost = consumed_cost + #{cost},
                   model_call_count = model_call_count + #{calls},
                   updated_at = current_timestamp
             where id = #{runId}
               and status = 'RUNNING'
               and (max_tokens is null or consumed_tokens + #{tokens} <= max_tokens)
               and (max_cost is null or consumed_cost + #{cost} <= max_cost)
               and (max_model_calls is null or model_call_count + #{calls} <= max_model_calls)
            """)
    int reserveModelUsage(
            @Param("runId") long runId,
            @Param("tokens") long tokens,
            @Param("cost") BigDecimal cost,
            @Param("calls") int calls
    );
}
