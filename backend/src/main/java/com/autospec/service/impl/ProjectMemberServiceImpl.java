package com.autospec.service.impl;

import com.autospec.entity.ProjectMember;
import com.autospec.mapper.ProjectMemberMapper;
import com.autospec.service.KnowledgeCorpusEpochService;
import com.autospec.service.ProjectMemberService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ProjectMemberServiceImpl extends ServiceImpl<ProjectMemberMapper, ProjectMember> implements ProjectMemberService {
    private final KnowledgeCorpusEpochService corpusEpochService;

    public ProjectMemberServiceImpl(KnowledgeCorpusEpochService corpusEpochService) {
        this.corpusEpochService = corpusEpochService;
    }

    @Override
    @Transactional
    public boolean save(ProjectMember entity) {
        boolean saved = super.save(entity);
        if (saved && entity != null && entity.getProjectId() != null) {
            corpusEpochService.bump(entity.getProjectId(), "PROJECT_MEMBER_ADDED");
        }
        return saved;
    }

    @Override
    @Transactional
    public boolean updateById(ProjectMember entity) {
        boolean updated = super.updateById(entity);
        if (updated && entity != null && entity.getProjectId() != null) {
            corpusEpochService.bump(entity.getProjectId(), "PROJECT_MEMBER_UPDATED");
        }
        return updated;
    }

    @Override
    @Transactional
    public boolean removeById(Serializable id) {
        ProjectMember existing = id == null ? null : getById(id);
        boolean removed = super.removeById(id);
        if (removed && existing != null && existing.getProjectId() != null) {
            corpusEpochService.bump(existing.getProjectId(), "PROJECT_MEMBER_REMOVED");
        }
        return removed;
    }

    @Override
    @Transactional
    public boolean remove(Wrapper<ProjectMember> queryWrapper) {
        List<ProjectMember> existing = list(queryWrapper);
        boolean removed = super.remove(queryWrapper);
        if (removed) {
            Set<Long> projectIds = new HashSet<>();
            existing.stream()
                    .map(ProjectMember::getProjectId)
                    .filter(java.util.Objects::nonNull)
                    .forEach(projectIds::add);
            projectIds.forEach(projectId -> corpusEpochService.bump(projectId, "PROJECT_MEMBER_REMOVED"));
        }
        return removed;
    }
}
