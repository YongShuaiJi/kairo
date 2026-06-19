package com.example.runtimemock.platform.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.runtimemock.platform.service.PlatformJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "runtime-mock.platform",
        name = {"worker.enabled", "kafka.enabled"}, havingValue = "true")
public class KafkaOutboxPublisher {

    private static final Logger logger = LoggerFactory.getLogger(KafkaOutboxPublisher.class);

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public KafkaOutboxPublisher(JdbcTemplate jdbcTemplate, KafkaTemplate<String, String> kafkaTemplate,
                                OutboxProperties properties, PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, kafkaTemplate, properties, Clock.systemUTC(), transactionManager);
    }

    KafkaOutboxPublisher(JdbcTemplate jdbcTemplate, KafkaTemplate<String, String> kafkaTemplate,
                         OutboxProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(
            initialDelayString = "${runtime-mock.platform.outbox.scheduler.initial-delay-ms:2000}",
            fixedDelayString = "${runtime-mock.platform.outbox.scheduler.fixed-delay-ms:5000}"
    )
    public void publishAvailableEvents() {
        List<OutboxEvent> events = claimBatch();
        for (OutboxEvent event : events) {
            publishOne(event);
        }
    }

    public List<OutboxEvent> claimBatch() {
        return transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            Instant visibilityDeadline = now.plus(properties.getVisibilityTimeout());
            List<OutboxEvent> events = jdbcTemplate.query("""
                    select id, aggregate_type, aggregate_id, event_type, payload_json, attempts
                      from outbox_event
                     where status in ('NEW', 'FAILED', 'PUBLISHING')
                       and available_at <= ?
                       and attempts < ?
                     order by created_at, id
                     limit ?
                     for update skip locked
                    """,
                    (rs, rowNum) -> new OutboxEvent(
                            rs.getString("id"),
                            rs.getString("aggregate_type"),
                            rs.getString("aggregate_id"),
                            rs.getString("event_type"),
                            rs.getString("payload_json"),
                            rs.getInt("attempts") + 1
                    ),
                    Timestamp.from(now),
                    properties.getMaxAttempts(),
                    properties.getBatchSize());
            for (OutboxEvent event : events) {
                jdbcTemplate.update("""
                        update outbox_event
                           set status = 'PUBLISHING', attempts = ?, available_at = ?, last_error = null
                         where id = ?
                        """, event.attempts(), Timestamp.from(visibilityDeadline), event.id());
            }
            return events;
        });
    }

    private void publishOne(OutboxEvent event) {
        String topic = properties.getTopicPrefix() + event.eventType();
        try {
            String envelope = PlatformJson.write(java.util.Map.of(
                    "eventId", event.id(),
                    "eventType", event.eventType(),
                    "aggregateType", event.aggregateType(),
                    "aggregateId", event.aggregateId(),
                    "payload", PlatformJson.readMap(event.payloadJson())
            ));
            kafkaTemplate.send(topic, event.key(), envelope)
                    .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            markPublished(event);
        } catch (Exception ex) {
            markFailed(event, ex);
        }
    }

    private void markPublished(OutboxEvent event) {
        jdbcTemplate.update("""
                update outbox_event
                   set status = 'PUBLISHED', published_at = ?, last_error = null
                 where id = ?
                """, Timestamp.from(clock.instant()), event.id());
    }

    private void markFailed(OutboxEvent event, Exception ex) {
        boolean exhausted = event.attempts() >= properties.getMaxAttempts();
        Instant nextAttempt = clock.instant().plus(properties.getRetryBackoff().multipliedBy(event.attempts()));
        jdbcTemplate.update("""
                update outbox_event
                   set status = ?, available_at = ?, last_error = ?
                 where id = ?
                """, exhausted ? "DEAD" : "FAILED", Timestamp.from(nextAttempt), abbreviateError(ex), event.id());
        logger.warn("Failed to publish outbox event {} to Kafka, attempt {}/{}",
                event.id(), event.attempts(), properties.getMaxAttempts(), ex);
    }

    private String abbreviateError(Exception ex) {
        String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        return message.length() <= 2048 ? message : message.substring(0, 2048);
    }
}
