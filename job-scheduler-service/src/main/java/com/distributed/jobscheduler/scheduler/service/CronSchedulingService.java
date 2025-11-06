package com.distributed.jobscheduler.scheduler.service;

import com.distributed.jobscheduler.scheduler.entity.JobEntity;
import com.distributed.jobscheduler.scheduler.repository.JobRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class CronSchedulingService {

    private final JobRepository jobRepository;
    private final JobService jobService;
    private final Map<String, LocalDateTime> lastExecutionTimes = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Cron scheduling service initialized");
        loadCronJobs();
    }

    @Scheduled(fixedDelay = 60000) // Check every minute
    public void checkScheduledJobs() {
        try {
            // Check cron jobs
            List<JobEntity> cronJobs = jobRepository.findAllCronJobs();
            for (JobEntity job : cronJobs) {
                checkAndExecuteCronJob(job);
            }

            // Check one-time scheduled jobs
            List<JobEntity> scheduledJobs = jobRepository.findScheduledJobsDue(LocalDateTime.now());
            for (JobEntity job : scheduledJobs) {
                try {
                    log.info("Executing scheduled job: {}", job.getId());
                    jobService.executeJob(job.getId());
                    // Clear scheduled time after execution
                    job.setScheduledAt(null);
                    jobRepository.save(job);
                } catch (Exception e) {
                    log.error("Failed to execute scheduled job: {}", job.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error in scheduled job check", e);
        }
    }

    private void checkAndExecuteCronJob(JobEntity job) {
        try {
            CronExpression cron = CronExpression.parse(job.getCronExpression());
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime lastExecution = lastExecutionTimes.get(job.getId());

            if (lastExecution == null) {
                lastExecution = now.minusMinutes(1);
            }

            LocalDateTime nextExecution = cron.next(lastExecution.atZone(ZoneId.systemDefault()))
                    .toLocalDateTime();

            if (nextExecution != null && !nextExecution.isAfter(now)) {
                log.info("Executing cron job: {} (cron: {})", job.getId(), job.getCronExpression());
                jobService.executeJob(job.getId());
                lastExecutionTimes.put(job.getId(), now);
            }
        } catch (Exception e) {
            log.error("Error checking cron job: {}", job.getId(), e);
        }
    }

    private void loadCronJobs() {
        List<JobEntity> cronJobs = jobRepository.findAllCronJobs();
        log.info("Loaded {} cron jobs", cronJobs.size());

        for (JobEntity job : cronJobs) {
            lastExecutionTimes.put(job.getId(), LocalDateTime.now());
        }
    }
}
