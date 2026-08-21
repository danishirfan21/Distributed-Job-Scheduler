package com.distributed.jobscheduler.worker.service;

import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.common.dto.JobStatusUpdateDTO;
import com.distributed.jobscheduler.common.enums.JobStatus;
import com.distributed.jobscheduler.common.enums.JobType;
import com.distributed.jobscheduler.worker.executor.JobExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobExecutionServiceTest {

    @Mock
    private StatusReportingService statusReportingService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lenientRedisStubs();
    }

    private void lenientRedisStubs() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private JobExecutionService newService(List<JobExecutor> executors) {
        return new JobExecutionService("worker-1", 2, 1, executors, statusReportingService, meterRegistry, redisTemplate);
    }

    @Test
    void executeJob_ShouldSkipWhenDedupKeyAlreadyClaimed() throws InterruptedException {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOperations.get(anyString())).thenReturn("worker-2");

        JobExecutor executor = mock(JobExecutor.class);
        JobExecutionService service = newService(List.of(executor));

        JobExecutionDTO execution = JobExecutionDTO.builder()
            .executionId("exec-1")
            .type(JobType.CUSTOM)
            .currentRetry(0)
            .maxRetries(3)
            .parameters(new HashMap<>())
            .build();

        service.executeJob(execution);
        Thread.sleep(200);

        verifyNoInteractions(executor);
        verifyNoInteractions(statusReportingService);
    }

    @Test
    void executeJob_ShouldReportRunningThenCompleted_WhenExecutorSucceeds() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        JobExecutor executor = mock(JobExecutor.class);
        when(executor.supports("CUSTOM")).thenReturn(true);
        Map<String, Object> result = Map.of("ok", true);
        when(executor.execute(any(JobExecutionDTO.class))).thenReturn(result);

        JobExecutionService service = newService(List.of(executor));

        JobExecutionDTO execution = JobExecutionDTO.builder()
            .executionId("exec-2")
            .type(JobType.CUSTOM)
            .currentRetry(0)
            .maxRetries(3)
            .parameters(new HashMap<>())
            .build();

        service.executeJob(execution);

        ArgumentCaptor<JobStatusUpdateDTO> captor = ArgumentCaptor.forClass(JobStatusUpdateDTO.class);
        verify(statusReportingService, timeout(2000).times(2)).reportStatus(captor.capture());

        List<JobStatusUpdateDTO> updates = captor.getAllValues();
        assertThat(updates.get(0).getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(updates.get(1).getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(updates.get(1).getResult()).isEqualTo(result);
    }

    @Test
    void executeJob_ShouldRequestRetry_WhenExecutorFailsAndRetriesRemain() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        JobExecutor executor = mock(JobExecutor.class);
        when(executor.supports("CUSTOM")).thenReturn(true);
        when(executor.execute(any(JobExecutionDTO.class))).thenThrow(new RuntimeException("boom"));

        JobExecutionService service = newService(List.of(executor));

        JobExecutionDTO execution = JobExecutionDTO.builder()
            .executionId("exec-3")
            .type(JobType.CUSTOM)
            .currentRetry(0)
            .maxRetries(3)
            .parameters(new HashMap<>())
            .build();

        service.executeJob(execution);

        verify(statusReportingService, timeout(2000)).reportRetry(eq(execution), anyString(), anyString());
        verify(statusReportingService, never()).reportStatus(argThat(u -> u.getStatus() == JobStatus.FAILED));
    }
}
