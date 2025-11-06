package com.distributed.jobscheduler.worker.service;

import com.distributed.jobscheduler.common.constants.KafkaTopics;
import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.common.dto.JobStatusUpdateDTO;
import com.distributed.jobscheduler.common.enums.JobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StatusReportingService {

    private final KafkaTemplate<String, JobStatusUpdateDTO> statusUpdateKafkaTemplate;
    private final KafkaTemplate<String, JobExecutionDTO> retryKafkaTemplate;

    public void reportStatus(JobStatusUpdateDTO update) {
        statusUpdateKafkaTemplate.send(KafkaTopics.JOB_STATUS_UPDATE, update.getExecutionId(), update)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.debug("Status update sent: executionId={}, status={}",
                                update.getExecutionId(), update.getStatus());
                    } else {
                        log.error("Failed to send status update: executionId={}",
                                update.getExecutionId(), ex);
                    }
                });
    }

    public void reportRetry(JobExecutionDTO execution, String errorMessage, String stackTrace) {
        log.info("Job will be retried: executionId={}, currentRetry={}, maxRetries={}",
                execution.getExecutionId(), execution.getCurrentRetry(), execution.getMaxRetries());

        execution.setCurrentRetry(execution.getCurrentRetry() + 1);
        execution.setStatus(JobStatus.RETRYING);
        execution.setErrorMessage(errorMessage);
        execution.setStackTrace(stackTrace);

        retryKafkaTemplate.send(KafkaTopics.JOB_RETRY, execution.getExecutionId(), execution)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Retry request sent: executionId={}, retry={}",
                                execution.getExecutionId(), execution.getCurrentRetry());
                    } else {
                        log.error("Failed to send retry request: executionId={}",
                                execution.getExecutionId(), ex);
                    }
                });
    }
}
