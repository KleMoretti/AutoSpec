package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("review_issue")
public class ReviewIssue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String severity;

    private String issueType;

    private String description;

    private String suggestion;

    private String status;

    private LocalDateTime createdAt;
}
