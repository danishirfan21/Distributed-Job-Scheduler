package com.distributed.jobscheduler.worker.consumer;

import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.worker.service.JobExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class JobRetryConsumerTest {

    @Mock
    private JobExecutionService jobExecutionService;

    private final JobRetryConsumer consumer = new JobRetryConsumer(jobExecutionService);

    @Test
    void calculateRetryDelay_ShouldApplyExponentialBackoff() {
        assertThat(consumer.calculateRetryDelay(executionWithRetry(1))).isEqualTo(60);
        assertThat(consumer.calculateRetryDelay(executionWithRetry(2))).isEqualTo(120);
        assertThat(consumer.calculateRetryDelay(executionWithRetry(3))).isEqualTo(240);
        assertThat(consumer.calculateRetryDelay(executionWithRetry(4))).isEqualTo(480);
    }

    private JobExecutionDTO executionWithRetry(int retry) {
        return JobExecutionDTO.builder()
            .executionId("exec-1")
            .currentRetry(retry)
            .maxRetries(5)
            .build();
    }
}
