package com.autospec.service.impl;

import com.autospec.entity.ModelConfig;
import com.autospec.mapper.ModelConfigMapper;
import com.autospec.service.ModelConfigService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ModelConfigServiceImpl extends ServiceImpl<ModelConfigMapper, ModelConfig> implements ModelConfigService {
}
