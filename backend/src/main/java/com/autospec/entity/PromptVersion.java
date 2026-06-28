package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("prompt_version")
public class PromptVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String promptKey;

    private String version;

    private String content;

    private String checksum;

    private Boolean active;

    private LocalDateTime createdAt;
}
