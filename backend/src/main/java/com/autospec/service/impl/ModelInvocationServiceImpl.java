package com.autospec.service.impl;

import com.autospec.entity.ModelInvocation;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.service.ModelInvocationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ModelInvocationServiceImpl extends ServiceImpl<ModelInvocationMapper, ModelInvocation> implements ModelInvocationService {
}
