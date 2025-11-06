package com.distributed.jobscheduler.scheduler.repository;

import com.distributed.jobscheduler.common.enums.JobType;
import com.distributed.jobscheduler.scheduler.entity.JobEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<JobEntity, String> {

    Optional<JobEntity> findByIdAndEnabledTrue(String id);

    Page<JobEntity> findAllByEnabledTrue(Pageable pageable);

    List<JobEntity> findByTypeAndEnabledTrue(JobType type);

    @Query("SELECT j FROM JobEntity j WHERE j.cronExpression IS NOT NULL AND j.enabled = true")
    List<JobEntity> findAllCronJobs();

    @Query("SELECT j FROM JobEntity j WHERE j.scheduledAt IS NOT NULL " +
           "AND j.scheduledAt <= :now AND j.enabled = true")
    List<JobEntity> findScheduledJobsDue(LocalDateTime now);

    List<JobEntity> findByTagsContainingAndEnabledTrue(String tag);
}
