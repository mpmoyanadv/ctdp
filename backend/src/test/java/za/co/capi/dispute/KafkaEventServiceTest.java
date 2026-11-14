package za.co.capi.dispute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import za.co.capi.dispute.infrastructure.messaging.KafkaEventService;
import za.co.capi.dispute.presentation.dto.ApiDtos.KafkaStreamStatusDto;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaEventServiceTest {

    @Test
    void incrementsPublishedOnlyOnBrokerAckAndTracksAsyncAndSyncFailures() {
        AtomicReference<Supplier<CompletableFuture<SendResult<String, String>>>> behavior = new AtomicReference<>();

        ProducerFactory<String, String> dummyFactory = new ProducerFactory<>() {
            @Override
            public Producer<String, String> createProducer() {
                return null;
            }
        };
        KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(dummyFactory) {
            @Override
            public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
                return behavior.get().get();
            }
        };

        KafkaEventService service = new KafkaEventService(kafkaTemplate, new ObjectMapper(), true);

        // 1. Successful broker acknowledgement
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("capi.dispute.lifecycle.events", 0), 0, 0, 0L, 0, 0);
        SendResult<String, String> okResult = new SendResult<>(null, metadata);
        behavior.set(() -> CompletableFuture.completedFuture(okResult));

        service.publishDisputeLifecycleEvent("DISPUTE_CREATED", "DSP-1", Map.of("k", "v"));

        KafkaStreamStatusDto afterSuccess = service.getStreamStatus(true, 300);
        assertThat(afterSuccess.totalEventsPublished()).isEqualTo(1);
        assertThat(afterSuccess.totalEventsFailed()).isEqualTo(0);
        assertThat(afterSuccess.deliveryGuarantee()).isEqualTo("BEST_EFFORT_AT_MOST_ONCE");

        // 2. Async broker failure
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Broker timeout"));
        behavior.set(() -> failedFuture);

        service.publishSecurityAuditEvent("LOGIN_FAILED", "user-1", Map.of());

        KafkaStreamStatusDto afterAsyncFail = service.getStreamStatus(true, 300);
        assertThat(afterAsyncFail.totalEventsPublished()).isEqualTo(1);
        assertThat(afterAsyncFail.totalEventsFailed()).isEqualTo(1);

        // 3. Synchronous KafkaException on send()
        behavior.set(() -> {
            throw new IllegalStateException("Producer closed");
        });

        service.publishAlertNotificationEvent("ALERT", "user-1", Map.of());

        KafkaStreamStatusDto afterSyncFail = service.getStreamStatus(true, 300);
        assertThat(afterSyncFail.totalEventsPublished()).isEqualTo(1);
        assertThat(afterSyncFail.totalEventsFailed()).isEqualTo(2);
    }

    @Test
    void usesInMemoryBusWhenKafkaIsDisabled() {
        KafkaEventService service = new KafkaEventService(null, new ObjectMapper(), false);

        service.publishDisputeLifecycleEvent("DISPUTE_CREATED", "DSP-2", Map.of());

        KafkaStreamStatusDto status = service.getStreamStatus(false, 300);
        assertThat(status.kafkaBrokerEnabled()).isFalse();
        assertThat(status.totalEventsPublished()).isEqualTo(1);
        assertThat(status.totalEventsFailed()).isEqualTo(0);
        assertThat(status.totalEventsConsumed()).isEqualTo(1);
        assertThat(status.recentEvents()).hasSize(1);
        assertThat(status.recentEvents().get(0).transportMode()).isEqualTo("IN_MEMORY_BUS");
    }
}
