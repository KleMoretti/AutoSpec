package com.autospec.service;

import com.autospec.entity.Project;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface ProjectService extends IService<Project> {

    Project createProject(String name, String requirement);

    List<Project> listVisibleProjects(Long userId, int limit, int offset);
}
