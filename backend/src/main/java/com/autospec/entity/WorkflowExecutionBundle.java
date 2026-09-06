package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_execution_bundle")
public class WorkflowExecutionBundle {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workflowVersionId;
    private String bundleVersion;
    private String bundleJson;
    private String bundleHash;
    private LocalDateTime createdAt;
}
