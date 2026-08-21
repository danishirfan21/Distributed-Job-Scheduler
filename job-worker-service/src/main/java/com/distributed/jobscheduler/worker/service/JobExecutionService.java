package com.distributed.jobscheduler.worker.service;

import com.distributed.jobscheduler.common.constants.RedisKeys;
import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.common.dto.JobStatusUpdateDTO;
import com.distributed.jobscheduler.common.enums.JobStatus;
import com.distributed.jobscheduler.worker.executor.JobExecutor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
@Slf4j
public class JobExecutionService {

    private final String workerId;
    private final ExecutorService executorService;
    private final List<JobExecutor> jobExecutors;
    private final StatusReportingService statusReportingService;
    private final MeterRegistry meterRegistry;
    private final RedisTemplate<String, String> redisTemplate;
    private final int jobTimeoutMinutes;
    private final Map<String, Future<?>> runningJobs = new ConcurrentHashMap<>();

    public JobExecutionService(
            @Value("${worker.id}") String workerId,
            @Value("${worker.max-concurrent-jobs:10}") int maxConcurrentJobs,
            @Value("${worker.job-timeout-minutes:30}") int jobTimeoutMinutes,
            List<JobExecutor> jobExecutors,
            StatusReportingService statusReportingService,
            MeterRegistry meterRegistry,
            RedisTemplate<String, String> redisTemplate) {

        this.workerId = workerId;
        this.jobTimeoutMinutes = jobTimeoutMinutes;
        this.jobExecutors = jobExecutors;
        this.statusReportingService = statusReportingService;
        this.meterRegistry = meterRegistry;
        this.redisTemplate = redisTemplate;
        this.executorService = Executors.newFixedThreadPool(maxConcurrentJobs,
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "job-executor-" + (++count));
                    }
                });

        log.info("Job Execution Service initialized: workerId={}, maxConcurrentJobs={}",
                workerId, maxConcurrentJobs);
    }

    public void executeJob(JobExecutionDTO execution) {
        // Guard against duplicate processing of the same delivery: Kafka only guarantees
        // at-least-once delivery, so a consumer rebalance (or two workers briefly sharing
        // a partition during a rebalance) can hand the same (executionId, retry) attempt
        // to more than one worker. Only the worker that wins this Redis SETNX actually runs it.
        String attemptKey = RedisKeys.jobAttempt(execution.getExecutionId(), execution.getCurrentRetry());
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(attemptKey, workerId, Duration.ofMinutes(jobTimeoutMinutes + 5L));

        if (!Boolean.TRUE.equals(acquired)) {
            log.warn("Duplicate delivery detected, skipping: executionId={}, retry={}, alreadyClaimedBy={}",
                    execution.getExecutionId(), execution.getCurrentRetry(),
                    redisTemplate.opsForValue().get(attemptKey));
            return;
        }

        log.info("Submitting job for execution: executionId={}, type={}",
                execution.getExecutionId(), execution.getType());

        Future<?> future = executorService.submit(() -> executeJobInternal(execution));
        runningJobs.put(execution.getExecutionId(), future);
    }

    private void executeJobInternal(JobExecutionDTO execution) {
        long startTime = System.currentTimeMillis();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Report job started
            statusReportingService.reportStatus(JobStatusUpdateDTO.builder()
                    .executionId(execution.getExecutionId())
                    .status(JobStatus.RUNNING)
                    .workerId(workerId)
                    .build());

            // Find appropriate executor
            JobExecutor executor = findExecutor(execution.getType().name());
            if (executor == null) {
                throw new IllegalArgumentException("No executor found for job type: " + execution.getType());
            }

            // Execute job with timeout
            Map<String, Object> result = executeWithTimeout(executor, execution);

            long executionTime = System.currentTimeMillis() - startTime;

            // Report success
            statusReportingService.reportStatus(JobStatusUpdateDTO.builder()
                    .executionId(execution.getExecutionId())
                    .status(JobStatus.COMPLETED)
                    .workerId(workerId)
                    .result(result)
                    .executionTimeMs(executionTime)
                    .build());

            // Record metrics
            sample.stop(Timer.builder("job.execution.time")
                    .tag("type", execution.getType().name())
                    .tag("status", "success")
                    .register(meterRegistry));

            Counter.builder("jobs.completed")
                    .tag("type", execution.getType().name())
                    .register(meterRegistry)
                    .increment();

            log.info("Job completed successfully: executionId={}, executionTime={}ms",
                    execution.getExecutionId(), executionTime);

        } catch (TimeoutException e) {
            handleJobFailure(execution, "Job execution timeout after " + jobTimeoutMinutes + " minutes", e, sample);
        } catch (Exception e) {
            handleJobFailure(execution, "Job execution failed: " + e.getMessage(), e, sample);
        } finally {
            runningJobs.remove(execution.getExecutionId());
        }
    }

    private Map<String, Object> executeWithTimeout(JobExecutor executor, JobExecutionDTO execution)
            throws Exception {

        ExecutorService timeoutExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> future = timeoutExecutor.submit(() -> executor.execute(execution));
            return future.get(jobTimeoutMinutes, TimeUnit.MINUTES);
        } finally {
            timeoutExecutor.shutdown();
        }
    }

    private void handleJobFailure(JobExecutionDTO execution, String errorMessage,
                                  Exception e, Timer.Sample sample) {
        long executionTime = System.currentTimeMillis();

        String stackTrace = getStackTrace(e);

        log.error("Job failed: executionId={}, error={}", execution.getExecutionId(), errorMessage, e);

        // Check if retry is needed
        if (execution.getCurrentRetry() < execution.getMaxRetries()) {
            statusReportingService.reportRetry(execution, errorMessage, stackTrace);
        } else {
            statusReportingService.reportStatus(JobStatusUpdateDTO.builder()
                    .executionId(execution.getExecutionId())
                    .status(JobStatus.FAILED)
                    .workerId(workerId)
                    .errorMessage(errorMessage)
                    .stackTrace(stackTrace)
                    .executionTimeMs(executionTime)
                    .build());
        }

        // Record metrics
        sample.stop(Timer.builder("job.execution.time")
                .tag("type", execution.getType().name())
                .tag("status", "failed")
                .register(meterRegistry));

        Counter.builder("jobs.failed")
                .tag("type", execution.getType().name())
                .register(meterRegistry)
                .increment();
    }

    private JobExecutor findExecutor(String jobType) {
        return jobExecutors.stream()
                .filter(executor -> executor.supports(jobType))
                .findFirst()
                .orElse(null);
    }

    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    public void shutdown() {
        log.info("Shutting down Job Execution Service");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
