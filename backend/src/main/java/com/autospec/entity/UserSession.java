package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_session")
public class UserSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tokenHash;

    private Long userId;

    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    private LocalDateTime createdAt;
}
