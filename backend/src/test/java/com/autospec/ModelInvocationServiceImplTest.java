package com.autospec;

import com.autospec.entity.ModelInvocation;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.observability.ModelInvocationMetrics;
import com.autospec.service.impl.ModelInvocationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelInvocationServiceImplTest {

    @Test
    void recordsMetricsOnlyAfterInvocationWasPersisted() {
        ModelInvocationMapper mapper = mock(ModelInvocationMapper.class);
        ModelInvocationMetrics metrics = mock(ModelInvocationMetrics.class);
        ModelInvocationServiceImpl service = service(mapper, metrics);
        ModelInvocation invocation = new ModelInvocation();
        when(mapper.insert(invocation)).thenReturn(1);

        assertThat(service.save(invocation)).isTrue();

        verify(metrics).record(invocation);
    }

    @Test
    void doesNotRecordMetricsWhenPersistenceFails() {
        ModelInvocationMapper mapper = mock(ModelInvocationMapper.class);
        ModelInvocationMetrics metrics = mock(ModelInvocationMetrics.class);
        ModelInvocationServiceImpl service = service(mapper, metrics);
        ModelInvocation invocation = new ModelInvocation();
        when(mapper.insert(invocation)).thenReturn(0);

        assertThat(service.save(invocation)).isFalse();

        verify(metrics, never()).record(invocation);
    }

    private ModelInvocationServiceImpl service(
            ModelInvocationMapper mapper,
            ModelInvocationMetrics metrics
    ) {
        ModelInvocationServiceImpl service = new ModelInvocationServiceImpl(metrics);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }
}
