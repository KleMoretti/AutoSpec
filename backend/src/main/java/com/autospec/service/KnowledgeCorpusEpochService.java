package com.autospec.service;

import com.autospec.entity.KnowledgeCorpusEpoch;
import com.autospec.mapper.KnowledgeCorpusEpochMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Owns the cache invalidation clock for project knowledge.
 *
 * The epoch is intentionally persisted in MySQL so a worker restart or Redis
 * eviction cannot make a stale protected result look current.
 */
@Service
public class KnowledgeCorpusEpochService {
    public static final long INITIAL_EPOCH = 1L;
    public static final String ACCESS_POLICY_VERSION = "project-member-active-expiry-v1";

    private final KnowledgeCorpusEpochMapper mapper;

    public KnowledgeCorpusEpochService(KnowledgeCorpusEpochMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public long current(Long projectId) {
        return ensure(projectId).getCorpusEpoch();
    }

    @Transactional
    public long bump(Long projectId, String reason) {
        KnowledgeCorpusEpoch current = ensure(projectId);
        String normalizedReason = reason == null || reason.isBlank()
                ? "EXPLICIT_INVALIDATION"
                : reason.trim();
        if (normalizedReason.length() > 128) {
            normalizedReason = normalizedReason.substring(0, 128);
        }
        int updated = mapper.update(null, new LambdaUpdateWrapper<KnowledgeCorpusEpoch>()
                .eq(KnowledgeCorpusEpoch::getId, current.getId())
                .setSql("corpus_epoch = corpus_epoch + 1")
                .set(KnowledgeCorpusEpoch::getLastInvalidationReason, normalizedReason)
                .set(KnowledgeCorpusEpoch::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw new IllegalStateException("Unable to advance knowledge corpus epoch");
        }
        return mapper.selectById(current.getId()).getCorpusEpoch();
    }

    private KnowledgeCorpusEpoch ensure(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required for corpus epoch");
        }
        KnowledgeCorpusEpoch existing = find(projectId);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        KnowledgeCorpusEpoch created = new KnowledgeCorpusEpoch();
        created.setProjectId(projectId);
        created.setCorpusEpoch(INITIAL_EPOCH);
        created.setAccessPolicyVersion(ACCESS_POLICY_VERSION);
        created.setLastInvalidationReason("INITIALIZED");
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            mapper.insert(created);
            return created;
        } catch (DuplicateKeyException duplicate) {
            KnowledgeCorpusEpoch concurrent = find(projectId);
            if (concurrent == null) {
                throw duplicate;
            }
            return concurrent;
        }
    }

    private KnowledgeCorpusEpoch find(Long projectId) {
        return mapper.selectOne(new LambdaQueryWrapper<KnowledgeCorpusEpoch>()
                .eq(KnowledgeCorpusEpoch::getProjectId, projectId)
                .last("limit 1"));
    }
}
