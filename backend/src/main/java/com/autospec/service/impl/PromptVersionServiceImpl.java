package com.autospec.service.impl;

import com.autospec.entity.PromptVersion;
import com.autospec.mapper.PromptVersionMapper;
import com.autospec.service.PromptVersionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class PromptVersionServiceImpl extends ServiceImpl<PromptVersionMapper, PromptVersion>
        implements PromptVersionService {
}
