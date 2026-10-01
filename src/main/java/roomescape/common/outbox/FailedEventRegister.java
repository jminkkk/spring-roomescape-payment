package roomescape.common.outbox;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FailedEventRegister {

    private static final Logger log = LoggerFactory.getLogger(FailedEventRegister.class);

    private final OutboxRepository outboxRepository;
    private final OutboxMapper outboxMapper;

    public FailedEventRegister(OutboxRepository outboxRepository, OutboxMapper outboxMapper) {
        this.outboxRepository = outboxRepository;
        this.outboxMapper = outboxMapper;
    }

    @Transactional(propagation = REQUIRES_NEW)
    public void register(OutboxEvent event) {
        log.warn("이벤트 등록 - type={}", event.eventType());
        Outbox outbox = outboxMapper.toOutboxEvent(event);
        outboxRepository.save(outbox);
    }
}