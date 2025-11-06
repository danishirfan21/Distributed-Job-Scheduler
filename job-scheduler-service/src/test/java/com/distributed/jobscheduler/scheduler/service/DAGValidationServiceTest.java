package com.distributed.jobscheduler.scheduler.service;

import com.distributed.jobscheduler.common.enums.JobType;
import com.distributed.jobscheduler.common.enums.Priority;
import com.distributed.jobscheduler.scheduler.entity.JobEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class DAGValidationServiceTest {

    private DAGValidationService dagValidationService;

    @BeforeEach
    void setUp() {
        dagValidationService = new DAGValidationService();
    }

    @Test
    void validateDAG_ShouldReturnTrueForNoDependencies() {
        // Given
        JobEntity job = JobEntity.builder()
            .id("job-1")
            .name("Job 1")
            .type(JobType.EMAIL_NOTIFICATION)
            .build();

        Map<String, JobEntity> allJobs = new HashMap<>();
        allJobs.put("job-1", job);

        // When
        boolean result = dagValidationService.validateDAG(job, allJobs);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void validateDAG_ShouldReturnTrueForValidDAG() {
        // Given
        JobEntity job1 = JobEntity.builder()
            .id("job-1")
            .name("Job 1")
            .type(JobType.EMAIL_NOTIFICATION)
            .build();

        JobEntity job2 = JobEntity.builder()
            .id("job-2")
            .name("Job 2")
            .type(JobType.REPORT_GENERATION)
            .dependencies(List.of("job-1"))
            .build();

        JobEntity job3 = JobEntity.builder()
            .id("job-3")
            .name("Job 3")
            .type(JobType.DATA_BACKUP)
            .dependencies(List.of("job-2"))
            .build();

        Map<String, JobEntity> allJobs = new HashMap<>();
        allJobs.put("job-1", job1);
        allJobs.put("job-2", job2);
        allJobs.put("job-3", job3);

        // When
        boolean result = dagValidationService.validateDAG(job3, allJobs);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void validateDAG_ShouldReturnFalseForCyclicDependency() {
        // Given - Create a cycle: job1 -> job2 -> job3 -> job1
        JobEntity job1 = JobEntity.builder()
            .id("job-1")
            .name("Job 1")
            .type(JobType.EMAIL_NOTIFICATION)
            .dependencies(List.of("job-3"))
            .build();

        JobEntity job2 = JobEntity.builder()
            .id("job-2")
            .name("Job 2")
            .type(JobType.REPORT_GENERATION)
            .dependencies(List.of("job-1"))
            .build();

        JobEntity job3 = JobEntity.builder()
            .id("job-3")
            .name("Job 3")
            .type(JobType.DATA_BACKUP)
            .dependencies(List.of("job-2"))
            .build();

        Map<String, JobEntity> allJobs = new HashMap<>();
        allJobs.put("job-1", job1);
        allJobs.put("job-2", job2);
        allJobs.put("job-3", job3);

        // When
        boolean result = dagValidationService.validateDAG(job1, allJobs);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void getTopologicalOrder_ShouldReturnCorrectOrder() {
        // Given
        JobEntity job1 = JobEntity.builder()
            .id("job-1")
            .name("Job 1")
            .type(JobType.EMAIL_NOTIFICATION)
            .build();

        JobEntity job2 = JobEntity.builder()
            .id("job-2")
            .name("Job 2")
            .type(JobType.REPORT_GENERATION)
            .dependencies(List.of("job-1"))
            .build();

        JobEntity job3 = JobEntity.builder()
            .id("job-3")
            .name("Job 3")
            .type(JobType.DATA_BACKUP)
            .dependencies(List.of("job-1"))
            .build();

        JobEntity job4 = JobEntity.builder()
            .id("job-4")
            .name("Job 4")
            .type(JobType.DATA_PROCESSING)
            .dependencies(List.of("job-2", "job-3"))
            .build();

        List<JobEntity> jobs = Arrays.asList(job1, job2, job3, job4);

        // When
        List<String> order = dagValidationService.getTopologicalOrder(jobs);

        // Then
        assertThat(order).hasSize(4);
        assertThat(order.indexOf("job-1")).isLessThan(order.indexOf("job-2"));
        assertThat(order.indexOf("job-1")).isLessThan(order.indexOf("job-3"));
        assertThat(order.indexOf("job-2")).isLessThan(order.indexOf("job-4"));
        assertThat(order.indexOf("job-3")).isLessThan(order.indexOf("job-4"));
    }

    @Test
    void areDependenciesCompleted_ShouldReturnTrueWhenAllCompleted() {
        // Given
        JobEntity job1 = JobEntity.builder()
            .id("job-1")
            .name("Job 1")
            .type(JobType.EMAIL_NOTIFICATION)
            .build();

        JobEntity job2 = JobEntity.builder()
            .id("job-2")
            .name("Job 2")
            .type(JobType.REPORT_GENERATION)
            .dependencies(List.of("job-1"))
            .build();

        Map<String, JobEntity> allJobs = new HashMap<>();
        allJobs.put("job-1", job1);
        allJobs.put("job-2", job2);

        Set<String> completedJobs = new HashSet<>(Arrays.asList("job-1"));

        // When
        boolean result = dagValidationService.areDependenciesCompleted("job-2", allJobs, completedJobs);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void areDependenciesCompleted_ShouldReturnFalseWhenNotAllCompleted() {
        // Given
        JobEntity job2 = JobEntity.builder()
            .id("job-2")
            .name("Job 2")
            .type(JobType.REPORT_GENERATION)
            .dependencies(List.of("job-1"))
            .build();

        Map<String, JobEntity> allJobs = new HashMap<>();
        allJobs.put("job-2", job2);

        Set<String> completedJobs = new HashSet<>();

        // When
        boolean result = dagValidationService.areDependenciesCompleted("job-2", allJobs, completedJobs);

        // Then
        assertThat(result).isFalse();
    }
}
