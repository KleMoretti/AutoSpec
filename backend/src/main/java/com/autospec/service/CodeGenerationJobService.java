package com.autospec.service;

import com.autospec.entity.CodeGenerationJob;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface CodeGenerationJobService extends IService<CodeGenerationJob> {

    List<CodeGenerationJob> listByProjectId(Long projectId, int limit, int offset);

    CodeGenerationJob cancelRunningJob(Long projectId, Long jobId);
}
