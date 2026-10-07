package roomescape.payment.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class PaymentEventTopicConfiguration {

    public static final String PAYMENT_PERSIST_FAILED = "payment.persist.failed";

    // 파티션은 늘릴 수만 있고 줄일 수 없어 1로 시작. DLT도 같은 수여야 한다.
    @Bean
    public NewTopic paymentPersistFailedTopic() {
        return TopicBuilder.name(PAYMENT_PERSIST_FAILED)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
