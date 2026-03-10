package com.distributed.jobscheduler.scheduler.controller;

import com.distributed.jobscheduler.common.dto.JobDefinitionDTO;
import com.distributed.jobscheduler.common.enums.JobType;
import com.distributed.jobscheduler.common.enums.Priority;
import com.distributed.jobscheduler.scheduler.entity.JobEntity;
import com.distributed.jobscheduler.scheduler.repository.JobExecutionRepository;
import com.distributed.jobscheduler.scheduler.repository.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class JobControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobExecutionRepository executionRepository;

    @BeforeEach
    void setUp() {
        executionRepository.deleteAll();
        jobRepository.deleteAll();
    }

    @Test
    @WithMockUser(username = "test-user")
    void createJob_ShouldReturnCreatedJob() throws Exception {
        // Given
        Map<String, Object> params = new HashMap<>();
        params.put("to", "test@example.com");
        params.put("subject", "Test");

        JobDefinitionDTO dto = JobDefinitionDTO.builder()
            .name("Test Job")
            .description("Test Description")
            .type(JobType.EMAIL_NOTIFICATION)
            .priority(Priority.HIGH)
            .parameters(params)
            .maxRetries(3)
            .build();

        // When & Then
        mockMvc.perform(post("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("Test Job"))
            .andExpect(jsonPath("$.data.type").value("EMAIL_NOTIFICATION"));
    }

    @Test
    @WithMockUser(username = "test-user")
    void getJob_ShouldReturnJob() throws Exception {
        // Given
        JobEntity job = JobEntity.builder()
            .name("Test Job")
            .type(JobType.EMAIL_NOTIFICATION)
            .priority(Priority.NORMAL)
            .enabled(true)
            .build();

        job = jobRepository.save(job);

        // When & Then
        mockMvc.perform(get("/api/v1/jobs/" + job.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(job.getId()))
            .andExpect(jsonPath("$.data.name").value("Test Job"));
    }

    @Test
    @WithMockUser(username = "test-user")
    void listJobs_ShouldReturnPageOfJobs() throws Exception {
        // Given
        JobEntity job1 = JobEntity.builder()
            .name("Job 1")
            .type(JobType.EMAIL_NOTIFICATION)
            .priority(Priority.NORMAL)
            .enabled(true)
            .build();

        JobEntity job2 = JobEntity.builder()
            .name("Job 2")
            .type(JobType.REPORT_GENERATION)
            .priority(Priority.HIGH)
            .enabled(true)
            .build();

        jobRepository.save(job1);
        jobRepository.save(job2);

        // When & Then
        mockMvc.perform(get("/api/v1/jobs")
                .param("page", "0")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @WithMockUser(username = "test-user")
    void deleteJob_ShouldSoftDeleteJob() throws Exception {
        // Given
        JobEntity job = JobEntity.builder()
            .name("Test Job")
            .type(JobType.EMAIL_NOTIFICATION)
            .priority(Priority.NORMAL)
            .enabled(true)
            .build();

        job = jobRepository.save(job);

        // When & Then
        mockMvc.perform(delete("/api/v1/jobs/" + job.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        // Verify job is soft deleted
        JobEntity deletedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(deletedJob.getEnabled()).isFalse();
    }
}
