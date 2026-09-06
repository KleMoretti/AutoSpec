package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Monotonic invalidation version for project-scoped knowledge. */
@Data
@TableName("knowledge_corpus_epoch")
public class KnowledgeCorpusEpoch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long corpusEpoch;
    private String accessPolicyVersion;
    private String lastInvalidationReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
