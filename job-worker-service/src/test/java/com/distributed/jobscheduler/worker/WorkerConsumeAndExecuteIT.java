package com.distributed.jobscheduler.worker;

import com.distributed.jobscheduler.common.constants.KafkaTopics;
import com.distributed.jobscheduler.common.dto.JobExecutionDTO;
import com.distributed.jobscheduler.common.enums.JobType;
import com.distributed.jobscheduler.common.enums.Priority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves job-worker-service's real behavior against a real Kafka broker and real Redis
 * (via Testcontainers - requires a Docker daemon): a message published to "job-dispatch"
 * (exactly as job-scheduler-service's KafkaProducerService publishes it) is picked up by
 * this service's own @KafkaListener, executed by the matching JobExecutor, and results in
 * RUNNING then COMPLETED JobStatusUpdateDTO messages on "job-status-update".
 */
@Testcontainers
@SpringBootTest
class WorkerConsumeAndExecuteIT {

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("worker.id", () -> "it-worker-1");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dispatchedJob_IsConsumedExecutedAndReportedCompleted() throws Exception {
        String executionId = UUID.randomUUID().toString();

        Map<String, Object> params = new HashMap<>();
        params.put("dataSource", "s3://bucket/input.csv");
        params.put("operation", "transform");

        JobExecutionDTO execution = JobExecutionDTO.builder()
            .jobId("job-it-1")
            .executionId(executionId)
            .name("IT Data Processing Job")
            .type(JobType.DATA_PROCESSING)
            .priority(Priority.NORMAL)
            .parameters(params)
            .currentRetry(0)
            .maxRetries(2)
            .build();

        try (KafkaProducer<String, String> producer = stringProducer()) {
            producer.send(new ProducerRecord<>(KafkaTopics.JOB_DISPATCH, executionId,
                objectMapper.writeValueAsString(execution))).get();
        }

        List<JsonNode> statusUpdates = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = stringConsumer("status-verify")) {
            consumer.subscribe(Collections.singletonList(KafkaTopics.JOB_STATUS_UPDATE));

            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline && !hasCompleted(statusUpdates)) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (executionId.equals(record.key())) {
                        statusUpdates.add(objectMapper.readTree(record.value()));
                    }
                }
            }
        }

        assertThat(statusUpdates).isNotEmpty();
        assertThat(statusUpdates.stream().anyMatch(n -> "RUNNING".equals(n.get("status").asText()))).isTrue();
        assertThat(hasCompleted(statusUpdates)).as("received a COMPLETED status update").isTrue();
    }

    private boolean hasCompleted(List<JsonNode> updates) {
        return updates.stream().anyMatch(n -> "COMPLETED".equals(n.get("status").asText()));
    }

    private KafkaConsumer<String, String> stringConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId + "-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private KafkaProducer<String, String> stringProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(props);
    }
}
