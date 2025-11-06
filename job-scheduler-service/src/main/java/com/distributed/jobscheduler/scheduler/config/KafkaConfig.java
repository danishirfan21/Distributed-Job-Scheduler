package com.distributed.jobscheduler.scheduler.config;

import com.distributed.jobscheduler.common.constants.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic jobDispatchTopic() {
        return TopicBuilder.name(KafkaTopics.JOB_DISPATCH)
                .partitions(10)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic jobStatusUpdateTopic() {
        return TopicBuilder.name(KafkaTopics.JOB_STATUS_UPDATE)
                .partitions(10)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic jobRetryTopic() {
        return TopicBuilder.name(KafkaTopics.JOB_RETRY)
                .partitions(10)
                .replicas(1)
                .build();
    }
}
