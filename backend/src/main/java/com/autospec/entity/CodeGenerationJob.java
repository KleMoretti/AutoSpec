package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("code_generation_job")
public class CodeGenerationJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String status;

    private String manifest;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}
