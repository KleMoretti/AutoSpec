package com.autospec.service.impl;

import com.autospec.entity.ModelInvocation;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.observability.ModelInvocationMetrics;
import com.autospec.service.ModelInvocationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelInvocationServiceImpl extends ServiceImpl<ModelInvocationMapper, ModelInvocation> implements ModelInvocationService {

    private final ModelInvocationMetrics metrics;

    public ModelInvocationServiceImpl(ModelInvocationMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean save(ModelInvocation invocation) {
        boolean saved = super.save(invocation);
        if (saved) {
            metrics.record(invocation);
        }
        return saved;
    }

    @Override
    public List<ModelInvocation> listByProjectId(Long projectId, int limit, int offset) {
        return lambdaQuery()
                .eq(ModelInvocation::getProjectId, projectId)
                .orderByAsc(ModelInvocation::getId)
                .last("limit " + limit + " offset " + offset)
                .list();
    }
}
