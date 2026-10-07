package roomescape.payment.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static roomescape.payment.event.PaymentEventTopicConfiguration.PAYMENT_PERSIST_FAILED;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import roomescape.util.KafkaIntegrationTest;

// @KafkaIntegrationTest 하네스가 동작한다는 증거. 지우지 말 것.
@KafkaIntegrationTest
class KafkaSmokeTest {

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private ConsumerFactory<Object, Object> consumerFactory;

    @DisplayName("임베디드 Kafka로 보낸 메시지를 받을 수 있다")
    @Test
    void sendAndReceive() {
        String payload = UUID.randomUUID().toString();
        List<Object> received = new ArrayList<>();

        try (Consumer<Object, Object> consumer = consumerFactory.createConsumer("smoke-" + payload, null)) {
            consumer.subscribe(List.of(PAYMENT_PERSIST_FAILED));
            kafkaTemplate.send(PAYMENT_PERSIST_FAILED, payload);

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(100)).forEach(record -> received.add(record.value()));
                assertThat(received).contains(payload);
            });
        }
    }
}
