package com.autospec.service;

import com.autospec.entity.Project;
import com.baomidou.mybatisplus.extension.service.IService;

public interface ProjectService extends IService<Project> {

    Project createProject(String name, String requirement);
}
