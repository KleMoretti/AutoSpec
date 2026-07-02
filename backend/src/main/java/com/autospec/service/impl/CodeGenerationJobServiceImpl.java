package com.autospec.service.impl;

import com.autospec.entity.CodeGenerationJob;
import com.autospec.mapper.CodeGenerationJobMapper;
import com.autospec.service.CodeGenerationJobService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class CodeGenerationJobServiceImpl extends ServiceImpl<CodeGenerationJobMapper, CodeGenerationJob> implements CodeGenerationJobService {
}
