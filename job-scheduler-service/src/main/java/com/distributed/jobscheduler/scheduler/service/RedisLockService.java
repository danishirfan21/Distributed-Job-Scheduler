package com.distributed.jobscheduler.scheduler.service;

import com.distributed.jobscheduler.common.constants.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisLockService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final Duration DEFAULT_LOCK_DURATION = Duration.ofMinutes(5);

    // Atomically checks that the caller still owns the lock before deleting it,
    // so a slow caller can never release a lock it no longer holds (e.g. one that
    // has since expired and been re-acquired by someone else).
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "return redis.call('del', KEYS[1]) " +
        "else " +
            "return 0 " +
        "end",
        Long.class
    );

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
        Long result = redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(lockKey), lockValue);
        boolean released = result != null && result == 1L;

        if (released) {
            log.debug("Lock released for job: {}", jobId);
        } else {
            log.warn("Lock value mismatch for job: {}. Cannot release lock.", jobId);
        }
        return released;
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
