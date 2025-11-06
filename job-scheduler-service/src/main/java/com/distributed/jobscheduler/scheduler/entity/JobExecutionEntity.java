package com.distributed.jobscheduler.scheduler.entity;

import com.distributed.jobscheduler.common.enums.JobStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "job_executions", indexes = {
    @Index(name = "idx_execution_job_id", columnList = "job_id"),
    @Index(name = "idx_execution_status", columnList = "status"),
    @Index(name = "idx_execution_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "job_id", nullable = false)
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "parameters", columnDefinition = "TEXT")
    private String parameters;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(name = "current_retry")
    @Builder.Default
    private Integer currentRetry = 0;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
