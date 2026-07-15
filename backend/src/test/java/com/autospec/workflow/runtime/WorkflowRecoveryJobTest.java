package com.autospec.workflow.runtime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class WorkflowRecoveryJobTest {

    @Test
    void skipsOverlappingInvocationInSameControlPlane() throws Exception {
        WorkflowRecoveryService service = mock(WorkflowRecoveryService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await();
            return new WorkflowRecoveryService.RecoveryResult(0, 0, 0);
        }).when(service).recover(any(), any());
        WorkflowRecoveryJob job = new WorkflowRecoveryJob(
                service,
                Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30)
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = executor.submit(job::recoverOnce);
            entered.await();

            assertThat(job.recoverOnce()).isFalse();
            release.countDown();
            assertThat(first.get()).isTrue();
        } finally {
            executor.shutdownNow();
        }
        verify(service, times(1)).recover(any(), any());
    }

    @Test
    void releasesGuardAfterFailure() {
        WorkflowRecoveryService service = mock(WorkflowRecoveryService.class);
        doAnswer(invocation -> {
            throw new IllegalStateException("database unavailable");
        }).doReturn(new WorkflowRecoveryService.RecoveryResult(0, 0, 0))
                .when(service).recover(any(), any());
        WorkflowRecoveryJob job = new WorkflowRecoveryJob(
                service, Clock.systemUTC(), Duration.ofSeconds(30)
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(job::recoverOnce)
                .isInstanceOf(IllegalStateException.class);
        assertThat(job.recoverOnce()).isTrue();
        verify(service, times(2)).recover(any(), any());
    }
}
