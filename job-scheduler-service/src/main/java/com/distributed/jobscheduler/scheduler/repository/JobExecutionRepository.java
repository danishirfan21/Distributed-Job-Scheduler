package com.distributed.jobscheduler.scheduler.repository;

import com.distributed.jobscheduler.common.enums.JobStatus;
import com.distributed.jobscheduler.scheduler.entity.JobExecutionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface JobExecutionRepository extends JpaRepository<JobExecutionEntity, String> {

    Page<JobExecutionEntity> findByJobId(String jobId, Pageable pageable);

    List<JobExecutionEntity> findByJobIdAndStatus(String jobId, JobStatus status);

    List<JobExecutionEntity> findByStatus(JobStatus status);

    Page<JobExecutionEntity> findByStatusIn(List<JobStatus> statuses, Pageable pageable);

    @Query("SELECT e FROM JobExecutionEntity e WHERE e.status IN :statuses " +
           "AND e.createdAt < :cutoffTime")
    List<JobExecutionEntity> findStaleExecutions(List<JobStatus> statuses, LocalDateTime cutoffTime);

    @Query("SELECT e FROM JobExecutionEntity e WHERE e.status = :status " +
           "AND e.createdAt < :cutoffTime")
    List<JobExecutionEntity> findOldExecutionsByStatus(JobStatus status, LocalDateTime cutoffTime);

    long countByJobIdAndStatus(String jobId, JobStatus status);
}
