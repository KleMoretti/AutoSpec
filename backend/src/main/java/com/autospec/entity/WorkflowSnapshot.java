package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_snapshot")
public class WorkflowSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String workflowKey;

    private String version;

    private String graphJson;

    private LocalDateTime createdAt;
}
