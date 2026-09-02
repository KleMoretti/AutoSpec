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

    @Update("""
            update workflow_run
               set reserved_tokens = reserved_tokens + #{tokens},
                   reserved_cost = reserved_cost + #{cost},
                   reserved_model_calls = reserved_model_calls + #{calls},
                   updated_at = current_timestamp
             where id = #{runId}
               and status = 'RUNNING'
               and (max_tokens is null
                    or consumed_tokens + reserved_tokens + #{tokens} <= max_tokens)
               and (max_cost is null
                    or consumed_cost + reserved_cost + #{cost} <= max_cost)
               and (max_model_calls is null
                    or model_call_count + reserved_model_calls + #{calls} <= max_model_calls)
            """)
    int reserveModelBudget(
            @Param("runId") long runId,
            @Param("tokens") long tokens,
            @Param("cost") BigDecimal cost,
            @Param("calls") int calls
    );

    @Update("""
            update workflow_run
               set reserved_tokens = reserved_tokens - #{reservedTokens},
                   reserved_cost = reserved_cost - #{reservedCost},
                   reserved_model_calls = reserved_model_calls - #{reservedCalls},
                   consumed_tokens = consumed_tokens + #{actualTokens},
                   consumed_cost = consumed_cost + #{actualCost},
                   model_call_count = model_call_count + #{actualCalls},
                   updated_at = current_timestamp
             where id = #{runId}
               and status = 'RUNNING'
               and reserved_tokens >= #{reservedTokens}
               and reserved_cost >= #{reservedCost}
               and reserved_model_calls >= #{reservedCalls}
            """)
    int settleModelBudget(
            @Param("runId") long runId,
            @Param("reservedTokens") long reservedTokens,
            @Param("reservedCost") BigDecimal reservedCost,
            @Param("reservedCalls") int reservedCalls,
            @Param("actualTokens") long actualTokens,
            @Param("actualCost") BigDecimal actualCost,
            @Param("actualCalls") int actualCalls
    );
}
