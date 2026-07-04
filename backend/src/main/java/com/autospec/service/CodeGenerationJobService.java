package com.autospec.service;

import com.autospec.entity.CodeGenerationJob;
import com.baomidou.mybatisplus.extension.service.IService;

public interface CodeGenerationJobService extends IService<CodeGenerationJob> {

    CodeGenerationJob cancelRunningJob(Long projectId, Long jobId);
}
