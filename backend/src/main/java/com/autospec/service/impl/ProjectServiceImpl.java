package com.autospec.service.impl;

import com.autospec.entity.Project;
import com.autospec.mapper.ProjectMapper;
import com.autospec.service.ProjectService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ProjectServiceImpl extends ServiceImpl<ProjectMapper, Project> implements ProjectService {

    @Override
    public Project createProject(String name, String requirement) {
        Project project = new Project();
        project.setUserId(0L);
        project.setName(name);
        project.setOriginalRequirement(requirement);
        project.setStatus("CREATED");
        save(project);
        return project;
    }
}
