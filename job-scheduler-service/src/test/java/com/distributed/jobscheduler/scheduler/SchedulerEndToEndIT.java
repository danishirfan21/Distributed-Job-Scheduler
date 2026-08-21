package com.distributed.jobscheduler.scheduler;

import com.distributed.jobscheduler.common.constants.KafkaTopics;
import com.distributed.jobscheduler.common.dto.JobDefinitionDTO;
import com.distributed.jobscheduler.common.dto.JobStatusUpdateDTO;
import com.distributed.jobscheduler.common.enums.JobStatus;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the real end-to-end flow this service is responsible for, against real
 * PostgreSQL, Kafka and Redis (via Testcontainers - requires a Docker daemon):
 *
 *   POST /api/v1/jobs               -> job persisted in Postgres
 *   POST /api/v1/jobs/{id}/execute  -> execution persisted (QUEUED) + message published to
 *                                      the real "job-dispatch" Kafka topic
 *   [simulated worker]              -> publishes JobStatusUpdateDTO(RUNNING) then (COMPLETED)
 *                                      to the real "job-status-update" topic, exactly as
 *                                      job-worker-service's StatusReportingService does
 *   GET /api/v1/jobs/executions/{id}-> execution status observed as COMPLETED
 *
 * The worker's own consume-and-execute behavior is covered independently by
 * job-worker-service's WorkerConsumeAndExecuteIT, since the two services are separate
 * deployable Spring Boot applications and don't share a JVM/classpath in production.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulerEndToEndIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("job_scheduler")
        .withUsername("postgres")
        .withPassword("postgres");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void jobLifecycle_CreateDispatchAndReportCompleted() throws Exception {
        // 1. Create job via REST API -> persisted in real Postgres
        Map<String, Object> params = new HashMap<>();
        params.put("dataSource", "s3://bucket/input.csv");
        params.put("operation", "transform");

        JobDefinitionDTO dto = JobDefinitionDTO.builder()
            .name("IT Data Processing Job")
            .type(JobType.DATA_PROCESSING)
            .priority(Priority.NORMAL)
            .parameters(params)
            .maxRetries(2)
            .build();

        ResponseEntity<String> createResponse = restTemplate.postForEntity(
            url("/api/v1/jobs"), dto, String.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode created = readData(createResponse.getBody());
        String jobId = created.get("id").asText();
        assertThat(jobId).isNotBlank();

        // 2. Execute job via REST API -> execution persisted + dispatched to real Kafka
        ResponseEntity<String> executeResponse = restTemplate.postForEntity(
            url("/api/v1/jobs/" + jobId + "/execute"), null, String.class);
        assertThat(executeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode executed = readData(executeResponse.getBody());
        String executionId = executed.get("executionId").asText();
        assertThat(executed.get("status").asText()).isEqualTo("QUEUED");

        // 3. Assert the dispatch really landed on the "job-dispatch" Kafka topic
        try (KafkaConsumer<String, String> dispatchConsumer = stringConsumer("dispatch-verify")) {
            dispatchConsumer.subscribe(Collections.singletonList(KafkaTopics.JOB_DISPATCH));
            ConsumerRecords<String, String> records = pollUntilRecords(dispatchConsumer);
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            boolean found = false;
            for (ConsumerRecord<String, String> record : records) {
                if (record.key().equals(executionId)) {
                    found = true;
                }
            }
            assertThat(found).as("dispatched message for executionId=" + executionId).isTrue();
        }

        // 4. Simulate the worker: publish RUNNING then COMPLETED to "job-status-update",
        //    exactly as job-worker-service's StatusReportingService does.
        try (KafkaProducer<String, String> producer = stringProducer()) {
            JobStatusUpdateDTO running = JobStatusUpdateDTO.builder()
                .executionId(executionId)
                .status(JobStatus.RUNNING)
                .workerId("it-simulated-worker")
                .build();
            producer.send(new ProducerRecord<>(KafkaTopics.JOB_STATUS_UPDATE, executionId,
                objectMapper.writeValueAsString(running))).get();

            Map<String, Object> result = new HashMap<>();
            result.put("recordsProcessed", 1000);
            JobStatusUpdateDTO completed = JobStatusUpdateDTO.builder()
                .executionId(executionId)
                .status(JobStatus.COMPLETED)
                .workerId("it-simulated-worker")
                .result(result)
                .executionTimeMs(1234L)
                .build();
            producer.send(new ProducerRecord<>(KafkaTopics.JOB_STATUS_UPDATE, executionId,
                objectMapper.writeValueAsString(completed))).get();
        }

        // 5. Poll GET /api/v1/jobs/executions/{id} until the scheduler's own Kafka
        //    consumer has processed the update and persisted COMPLETED.
        String finalStatus = null;
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<String> getResponse = restTemplate.getForEntity(
                url("/api/v1/jobs/executions/" + executionId), String.class);
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            finalStatus = readData(getResponse.getBody()).get("status").asText();
            if ("COMPLETED".equals(finalStatus)) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(finalStatus).isEqualTo("COMPLETED");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode readData(String body) throws Exception {
        return objectMapper.readTree(body).get("data");
    }

    private ConsumerRecords<String, String> pollUntilRecords(KafkaConsumer<String, String> consumer) {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records;
            }
        }
        return ConsumerRecords.empty();
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
