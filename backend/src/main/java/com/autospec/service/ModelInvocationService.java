package com.autospec.service;

import com.autospec.entity.ModelInvocation;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface ModelInvocationService extends IService<ModelInvocation> {

    List<ModelInvocation> listByProjectId(Long projectId, int limit, int offset);
}
