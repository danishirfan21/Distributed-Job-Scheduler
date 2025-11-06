package com.distributed.jobscheduler.worker.consumer;

import com.distributed.jobscheduler.common.constants.KafkaTopics;
import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.worker.service.JobExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobRetryConsumer {

    private final JobExecutionService jobExecutionService;

    @KafkaListener(topics = KafkaTopics.JOB_RETRY, groupId = "${spring.kafka.consumer.group-id}")
    public void consumeRetry(JobExecutionDTO execution) {
        log.info("Received retry request: executionId={}, retry={}/{}",
                execution.getExecutionId(), execution.getCurrentRetry(), execution.getMaxRetries());

        try {
            // Apply exponential backoff
            int retryDelay = calculateRetryDelay(execution);
            log.info("Waiting {}s before retry", retryDelay);
            TimeUnit.SECONDS.sleep(retryDelay);

            jobExecutionService.executeJob(execution);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Retry interrupted: executionId={}", execution.getExecutionId(), e);
        } catch (Exception e) {
            log.error("Failed to retry job: executionId={}", execution.getExecutionId(), e);
        }
    }

    private int calculateRetryDelay(JobExecutionDTO execution) {
        // Exponential backoff: retryDelay * 2^(currentRetry - 1)
        // For example: 60s, 120s, 240s...
        int baseDelay = 60; // Default 60 seconds
        int retryNumber = execution.getCurrentRetry();

        return (int) (baseDelay * Math.pow(2, retryNumber - 1));
    }
}
