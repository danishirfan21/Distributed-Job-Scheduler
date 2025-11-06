package com.distributed.jobscheduler.scheduler.service;

import com.distributed.jobscheduler.common.constants.KafkaTopics;
import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {

    private final KafkaTemplate<String, JobExecutionDTO> kafkaTemplate;

    public void dispatchJob(JobExecutionDTO execution) {
        CompletableFuture<SendResult<String, JobExecutionDTO>> future =
            kafkaTemplate.send(KafkaTopics.JOB_DISPATCH, execution.getExecutionId(), execution);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Job dispatched successfully: executionId={}, partition={}, offset={}",
                    execution.getExecutionId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            } else {
                log.error("Failed to dispatch job: executionId={}", execution.getExecutionId(), ex);
            }
        });
    }

    public void retryJob(JobExecutionDTO execution) {
        CompletableFuture<SendResult<String, JobExecutionDTO>> future =
            kafkaTemplate.send(KafkaTopics.JOB_RETRY, execution.getExecutionId(), execution);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Job retry dispatched: executionId={}, retry={}",
                    execution.getExecutionId(), execution.getCurrentRetry());
            } else {
                log.error("Failed to dispatch retry: executionId={}", execution.getExecutionId(), ex);
            }
        });
    }
}
