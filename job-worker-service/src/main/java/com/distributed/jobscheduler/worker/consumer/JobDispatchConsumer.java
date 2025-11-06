package com.distributed.jobscheduler.worker.consumer;

import com.distributed.jobscheduler.common.constants.KafkaTopics;
import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.worker.service.JobExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobDispatchConsumer {

    private final JobExecutionService jobExecutionService;

    @KafkaListener(topics = KafkaTopics.JOB_DISPATCH, groupId = "${spring.kafka.consumer.group-id}")
    public void consumeJob(JobExecutionDTO execution) {
        log.info("Received job: executionId={}, type={}, priority={}",
                execution.getExecutionId(), execution.getType(), execution.getPriority());

        try {
            jobExecutionService.executeJob(execution);
        } catch (Exception e) {
            log.error("Failed to submit job for execution: executionId={}",
                    execution.getExecutionId(), e);
        }
    }
}
