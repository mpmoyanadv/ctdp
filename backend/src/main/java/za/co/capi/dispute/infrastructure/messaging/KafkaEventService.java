package za.co.capi.dispute.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import za.co.capi.dispute.presentation.dto.ApiDtos.KafkaEventEnvelopeDto;
import za.co.capi.dispute.presentation.dto.ApiDtos.KafkaStreamStatusDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class KafkaEventService {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventService.class);
    private static final int MAX_RECENT_EVENTS = 200;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final boolean kafkaEnabled;
    private final ApplicationEventPublisher eventPublisher;

    private final Deque<KafkaEventEnvelopeDto> recentEvents = new ConcurrentLinkedDeque<>();
    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);
    private final AtomicLong consumedCount = new AtomicLong(0);

    @Autowired
    public KafkaEventService(
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${app.kafka.enabled:true}") boolean kafkaEnabled,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaEnabled = kafkaEnabled;
        this.eventPublisher = eventPublisher;
    }

    public KafkaEventService(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            boolean kafkaEnabled) {
        this(kafkaTemplate, objectMapper, kafkaEnabled, null);
    }

    public void publishDisputeLifecycleEvent(String eventType, String disputeKey, Map<String, Object> payload) {
        enqueueOrPublish(KafkaTopicConfig.TOPIC_DISPUTE_LIFECYCLE, eventType, disputeKey, payload);
    }

    public void publishSecurityAuditEvent(String eventType, String entityKey, Map<String, Object> payload) {
        enqueueOrPublish(KafkaTopicConfig.TOPIC_SECURITY_AUDIT, eventType, entityKey, payload);
    }

    public void publishAlertNotificationEvent(String alertType, String recipientKey, Map<String, Object> payload) {
        enqueueOrPublish(KafkaTopicConfig.TOPIC_NOTIFICATION_ALERTS, alertType, recipientKey, payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleAfterCommitKafkaEvent(PendingKafkaEvent event) {
        publishEvent(event.topic(), event.eventType(), event.messageKey(), event.payload());
    }

    private void enqueueOrPublish(String topic, String eventType, String messageKey, Map<String, Object> payload) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new PendingKafkaEvent(topic, eventType, messageKey, payload));
        } else {
            publishEvent(topic, eventType, messageKey, payload);
        }
    }

    private void publishEvent(String topic, String eventType, String messageKey, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String serializedPayload = toJson(Map.of(
                "eventId", eventId,
                "topic", topic,
                "eventType", eventType,
                "key", messageKey != null ? messageKey : "SYSTEM",
                "timestamp", now.toString(),
                "payload", payload != null ? payload : Map.of()
        ));

        KafkaEventEnvelopeDto envelope = new KafkaEventEnvelopeDto(
                eventId,
                topic,
                eventType,
                messageKey != null ? messageKey : "SYSTEM",
                serializedPayload,
                kafkaEnabled && kafkaTemplate != null ? "KAFKA_BROKER" : "IN_MEMORY_BUS",
                now
        );
        recordEvent(envelope);

        if (kafkaEnabled && kafkaTemplate != null) {
            try {
                kafkaTemplate.send(topic, messageKey != null ? messageKey : eventId, serializedPayload)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                failedCount.incrementAndGet();
                                log.warn("Kafka broker send failed for topic {} ({}): {}", topic, eventType, ex.getMessage());
                            } else {
                                publishedCount.incrementAndGet();
                                log.info("Published Kafka event [{}] to topic {} partition {}",
                                        eventType, topic, result.getRecordMetadata().partition());
                            }
                        });
            } catch (Exception ex) {
                failedCount.incrementAndGet();
                log.warn("Kafka broker unavailable during publish to {} ({}): {}", topic, eventType, ex.getMessage());
            }
        } else {
            publishedCount.incrementAndGet();
            consumedCount.incrementAndGet();
        }
    }

    @KafkaListener(
            topics = {
                    KafkaTopicConfig.TOPIC_DISPUTE_LIFECYCLE,
                    KafkaTopicConfig.TOPIC_SECURITY_AUDIT,
                    KafkaTopicConfig.TOPIC_NOTIFICATION_ALERTS
            },
            groupId = "capi-dispute-portal-consumer-group"
    )
    public void onKafkaMessage(ConsumerRecord<String, String> record) {
        consumedCount.incrementAndGet();
        log.info("Consumed Kafka record from topic={} partition={} offset={} key={}",
                record.topic(), record.partition(), record.offset(), record.key());
    }

    public KafkaStreamStatusDto getStreamStatus(boolean redisAvailable, long idempotencyTtlSeconds) {
        return new KafkaStreamStatusDto(
                kafkaEnabled,
                redisAvailable,
                idempotencyTtlSeconds,
                List.of(
                        KafkaTopicConfig.TOPIC_DISPUTE_LIFECYCLE,
                        KafkaTopicConfig.TOPIC_SECURITY_AUDIT,
                        KafkaTopicConfig.TOPIC_NOTIFICATION_ALERTS
                ),
                publishedCount.get(),
                failedCount.get(),
                consumedCount.get(),
                "BEST_EFFORT_AT_MOST_ONCE",
                new ArrayList<>(recentEvents)
        );
    }

    private void recordEvent(KafkaEventEnvelopeDto event) {
        recentEvents.addFirst(event);
        while (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.pollLast();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    public record PendingKafkaEvent(
            String topic,
            String eventType,
            String messageKey,
            Map<String, Object> payload
    ) {}
}
