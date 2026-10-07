package roomescape.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

// 토픽은 @EmbeddedKafka가 아니라 앱의 @Bean NewTopic이 만든다 → 테스트가 실제 선언(파티션 수·retention)을 그대로 쓴다.
// 브로커 auto-create를 꺼서 선언하지 않은 토픽명은 테스트에서도 에러가 나게 한다 (docker-compose와 동일).
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@IntegrationTest
@EmbeddedKafka(
        brokerProperties = "auto.create.topics.enable=false",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@TestPropertySource(properties = "spring.kafka.admin.auto-create=true")
public @interface KafkaIntegrationTest {
}
