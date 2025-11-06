package com.distributed.jobscheduler.worker.service;

import com.distributed.jobscheduler.common.constants.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class HeartbeatService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${worker.id}")
    private String workerId;

    @Scheduled(fixedDelayString = "${worker.heartbeat-interval-seconds:30}000")
    public void sendHeartbeat() {
        try {
            String key = RedisKeys.workerHeartbeat(workerId);
            redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()),
                    Duration.ofMinutes(2));

            log.debug("Heartbeat sent: workerId={}", workerId);
        } catch (Exception e) {
            log.error("Failed to send heartbeat", e);
        }
    }
}
