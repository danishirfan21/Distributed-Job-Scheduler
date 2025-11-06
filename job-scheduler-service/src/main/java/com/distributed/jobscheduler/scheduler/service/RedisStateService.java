package com.distributed.jobscheduler.scheduler.service;

import com.distributed.jobscheduler.common.constants.RedisKeys;
import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisStateService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final Duration STATE_TTL = Duration.ofHours(24);

    public void saveExecutionState(String executionId, JobExecutionDTO execution) {
        try {
            String key = RedisKeys.jobExecution(executionId);
            String value = objectMapper.writeValueAsString(execution);
            redisTemplate.opsForValue().set(key, value, STATE_TTL);
            log.debug("Saved execution state for: {}", executionId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize execution state: {}", executionId, e);
        }
    }

    public JobExecutionDTO getExecutionState(String executionId) {
        try {
            String key = RedisKeys.jobExecution(executionId);
            String value = redisTemplate.opsForValue().get(key);

            if (value != null) {
                return objectMapper.readValue(value, JobExecutionDTO.class);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize execution state: {}", executionId, e);
        }
        return null;
    }

    public void deleteExecutionState(String executionId) {
        String key = RedisKeys.jobExecution(executionId);
        redisTemplate.delete(key);
        log.debug("Deleted execution state for: {}", executionId);
    }

    public void recordWorkerHeartbeat(String workerId) {
        String key = RedisKeys.workerHeartbeat(workerId);
        redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()), Duration.ofMinutes(2));
    }

    public boolean isWorkerAlive(String workerId) {
        String key = RedisKeys.workerHeartbeat(workerId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
