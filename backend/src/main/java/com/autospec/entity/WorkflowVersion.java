package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_version")
public class WorkflowVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long definitionId;
    private String version;
    private String specJson;
    private String contentHash;
    private String status;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
}
