package com.autospec.service.impl;

import com.autospec.entity.ModelProvider;
import com.autospec.mapper.ModelProviderMapper;
import com.autospec.service.ModelProviderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ModelProviderServiceImpl extends ServiceImpl<ModelProviderMapper, ModelProvider> implements ModelProviderService {
}
