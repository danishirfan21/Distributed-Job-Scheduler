package com.distributed.jobscheduler.worker.executor;

import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.common.enums.JobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailNotificationExecutorTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailNotificationExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new EmailNotificationExecutor(mailSender);
    }

    @Test
    void execute_ShouldSendEmailSuccessfully() throws Exception {
        // Given
        Map<String, Object> params = new HashMap<>();
        params.put("to", "test@example.com");
        params.put("subject", "Test Subject");
        params.put("body", "Test Body");

        JobExecutionDTO execution = JobExecutionDTO.builder()
            .executionId("exec-123")
            .type(JobType.EMAIL_NOTIFICATION)
            .parameters(params)
            .build();

        // When
        Map<String, Object> result = executor.execute(execution);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.get("status")).isEqualTo("sent");
        assertThat(result.get("recipient")).isEqualTo("test@example.com");
        assertThat(result.get("timestamp")).isNotNull();
    }

    @Test
    void supports_ShouldReturnTrueForEmailNotificationType() {
        // When
        boolean result = executor.supports(JobType.EMAIL_NOTIFICATION.name());

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void supports_ShouldReturnFalseForOtherTypes() {
        // When
        boolean result = executor.supports(JobType.REPORT_GENERATION.name());

        // Then
        assertThat(result).isFalse();
    }
}
