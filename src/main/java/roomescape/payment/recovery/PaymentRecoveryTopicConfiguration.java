package roomescape.payment.recovery;

import static roomescape.payment.event.PaymentEventTopicConfiguration.PAYMENT_PERSIST_FAILED;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class PaymentRecoveryTopicConfiguration {

    // DeadLetterPublishingRecoverer 기본 네이밍(원본 + ".DLT")
    public static final String PAYMENT_PERSIST_FAILED_DLT = PAYMENT_PERSIST_FAILED + ".DLT";

    // 수동 재투입 전제라 retention이 유실 방지선이다. 기본값(7일)에 의존하지 않고 30일 명시.
    // DeadLetterPublishingRecoverer는 원본과 같은 파티션 번호로 보내므로 파티션 수를 원본과 맞춘다.
    @Bean
    public NewTopic paymentPersistFailedDltTopic() {
        return TopicBuilder.name(PAYMENT_PERSIST_FAILED_DLT)
                .partitions(1)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(30).toMillis()))
                .build();
    }
}
