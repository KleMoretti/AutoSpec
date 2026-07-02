package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_config")
public class ModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String providerKey;

    private String modelName;

    private String agentNode;

    private Integer priority;

    private Boolean enabled;

    private LocalDateTime createdAt;
}
