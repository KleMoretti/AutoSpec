package com.autospec.service.impl;

import com.autospec.entity.Artifact;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.service.ArtifactService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ArtifactServiceImpl extends ServiceImpl<ArtifactMapper, Artifact> implements ArtifactService {

    @Override
    public List<Artifact> listByProjectId(Long projectId, int limit, int offset) {
        return lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .orderByAsc(Artifact::getId)
                .last("limit " + limit + " offset " + offset)
                .list();
    }

    @Override
    public List<Artifact> listVersionsByProjectIdAndType(Long projectId, String type, int limit, int offset) {
        return lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, type)
                .orderByAsc(Artifact::getVersion)
                .orderByAsc(Artifact::getId)
                .last("limit " + limit + " offset " + offset)
                .list();
    }
}
