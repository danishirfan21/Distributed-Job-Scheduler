package com.distributed.jobscheduler.scheduler.service;

import com.distributed.jobscheduler.common.constants.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisLockService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final Duration DEFAULT_LOCK_DURATION = Duration.ofMinutes(5);

    public String acquireLock(String jobId, Duration duration) {
        String lockKey = RedisKeys.jobLock(jobId);
        String lockValue = UUID.randomUUID().toString();

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, duration != null ? duration : DEFAULT_LOCK_DURATION);

        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Lock acquired for job: {} with value: {}", jobId, lockValue);
            return lockValue;
        }

        log.debug("Failed to acquire lock for job: {}", jobId);
        return null;
    }

    public boolean releaseLock(String jobId, String lockValue) {
        String lockKey = RedisKeys.jobLock(jobId);
        String currentValue = redisTemplate.opsForValue().get(lockKey);

        if (lockValue.equals(currentValue)) {
            Boolean deleted = redisTemplate.delete(lockKey);
            log.debug("Lock released for job: {}", jobId);
            return Boolean.TRUE.equals(deleted);
        }

        log.warn("Lock value mismatch for job: {}. Cannot release lock.", jobId);
        return false;
    }

    public boolean isLocked(String jobId) {
        String lockKey = RedisKeys.jobLock(jobId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey));
    }

    public void extendLock(String jobId, String lockValue, Duration additionalDuration) {
        String lockKey = RedisKeys.jobLock(jobId);
        String currentValue = redisTemplate.opsForValue().get(lockKey);

        if (lockValue.equals(currentValue)) {
            redisTemplate.expire(lockKey, additionalDuration);
            log.debug("Lock extended for job: {}", jobId);
        }
    }
}
