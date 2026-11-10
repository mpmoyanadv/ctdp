package za.co.capi.dispute.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
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

    private final Deque<KafkaEventEnvelopeDto> recentEvents = new ConcurrentLinkedDeque<>();
    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong consumedCount = new AtomicLong(0);

    public KafkaEventService(
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${app.kafka.enabled:true}") boolean kafkaEnabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaEnabled = kafkaEnabled;
    }

    public void publishDisputeLifecycleEvent(String eventType, String disputeKey, Map<String, Object> payload) {
        publishEvent(KafkaTopicConfig.TOPIC_DISPUTE_LIFECYCLE, eventType, disputeKey, payload);
    }

    public void publishSecurityAuditEvent(String eventType, String entityKey, Map<String, Object> payload) {
        publishEvent(KafkaTopicConfig.TOPIC_SECURITY_AUDIT, eventType, entityKey, payload);
    }

    public void publishAlertNotificationEvent(String alertType, String recipientKey, Map<String, Object> payload) {
        publishEvent(KafkaTopicConfig.TOPIC_NOTIFICATION_ALERTS, alertType, recipientKey, payload);
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
        publishedCount.incrementAndGet();

        if (kafkaEnabled && kafkaTemplate != null) {
            try {
                kafkaTemplate.send(topic, messageKey != null ? messageKey : eventId, serializedPayload)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.debug("Kafka broker send fallback for topic {} ({}): {}", topic, eventType, ex.getMessage());
                            } else {
                                log.info("Published Kafka event [{}] to topic {} partition {}",
                                        eventType, topic, result.getRecordMetadata().partition());
                            }
                        });
            } catch (Exception ex) {
                log.debug("Kafka broker unavailable during publish to {} ({}): {}", topic, eventType, ex.getMessage());
            }
        } else {
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
                consumedCount.get(),
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
}

