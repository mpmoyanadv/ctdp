package za.co.capitec.dispute.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTopicConfig {

    public static final String TOPIC_DISPUTE_LIFECYCLE = "capitec.dispute.lifecycle.events";
    public static final String TOPIC_SECURITY_AUDIT = "capitec.security.audit.events";
    public static final String TOPIC_NOTIFICATION_ALERTS = "capitec.notification.alert.events";

    @Bean
    public NewTopic disputeLifecycleTopic() {
        return TopicBuilder.name(TOPIC_DISPUTE_LIFECYCLE)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic securityAuditTopic() {
        return TopicBuilder.name(TOPIC_SECURITY_AUDIT)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationAlertsTopic() {
        return TopicBuilder.name(TOPIC_NOTIFICATION_ALERTS)
                .partitions(3)
                .replicas(1)
                .build();
    }
}

