package com.autospec.service.impl;

import com.autospec.entity.Artifact;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.service.ArtifactService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ArtifactServiceImpl extends ServiceImpl<ArtifactMapper, Artifact> implements ArtifactService {
}
