package com.distributed.jobscheduler.scheduler.consumer;

import com.distributed.jobscheduler.common.constants.KafkaTopics;
import com.distributed.jobscheduler.common.dto.JobStatusUpdateDTO;
import com.distributed.jobscheduler.scheduler.service.JobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobStatusConsumer {

    private final JobService jobService;

    @KafkaListener(topics = KafkaTopics.JOB_STATUS_UPDATE, groupId = "job-scheduler-group")
    public void consumeStatusUpdate(JobStatusUpdateDTO update) {
        log.info("Received status update: executionId={}, status={}",
            update.getExecutionId(), update.getStatus());

        try {
            jobService.updateExecutionStatus(
                update.getExecutionId(),
                update.getStatus(),
                update.getWorkerId(),
                update.getResult(),
                update.getErrorMessage(),
                update.getStackTrace(),
                update.getExecutionTimeMs()
            );
        } catch (Exception e) {
            log.error("Failed to process status update: {}", update.getExecutionId(), e);
        }
    }
}
