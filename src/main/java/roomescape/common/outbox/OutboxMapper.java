package roomescape.common.outbox;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class OutboxMapper {

    private final ObjectMapper objectMapper;

    public OutboxMapper(ObjectMapper objectMapper) {this.objectMapper = objectMapper;}

    public Outbox toOutboxEvent(Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            return new Outbox(event.getClass().getName(), payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("이벤트 직렬화 실패: " + event, e);
        }
    }

    public <T> T toDomain(Outbox outbox, Class<T> clazz) {
        try {
            return objectMapper.readValue(outbox.getPayload(), clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Outbox 역직렬화 실패: " + outbox.getId(), e);
        }
    }
}
