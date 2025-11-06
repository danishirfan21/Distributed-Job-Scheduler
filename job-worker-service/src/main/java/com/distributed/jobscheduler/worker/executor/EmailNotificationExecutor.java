package com.distributed.jobscheduler.worker.executor;

import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.common.enums.JobType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailNotificationExecutor implements JobExecutor {

    private final JavaMailSender mailSender;

    @Override
    public Map<String, Object> execute(JobExecutionDTO execution) throws Exception {
        Map<String, Object> params = execution.getParameters();

        String to = (String) params.get("to");
        String subject = (String) params.get("subject");
        String body = (String) params.get("body");

        log.info("Sending email to: {}, subject: {}", to, subject);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        // Uncomment to actually send email
        // mailSender.send(message);

        log.info("Email sent successfully (simulated)");

        Map<String, Object> result = new HashMap<>();
        result.put("status", "sent");
        result.put("recipient", to);
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    @Override
    public boolean supports(String jobType) {
        return JobType.EMAIL_NOTIFICATION.name().equals(jobType);
    }
}
