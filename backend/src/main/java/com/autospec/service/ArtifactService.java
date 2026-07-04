package com.autospec.service;

import com.autospec.entity.Artifact;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface ArtifactService extends IService<Artifact> {

    List<Artifact> listByProjectId(Long projectId, int limit, int offset);

    List<Artifact> listVersionsByProjectIdAndType(Long projectId, String type, int limit, int offset);
}
