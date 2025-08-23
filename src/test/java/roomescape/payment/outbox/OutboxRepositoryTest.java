package roomescape.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import roomescape.common.outbox.Outbox;
import roomescape.common.outbox.OutboxRepository;
import roomescape.common.outbox.OutboxStatus;
import roomescape.util.JpaRepositoryTest;

@JpaRepositoryTest
class OutboxRepositoryTest {

    @Autowired private OutboxRepository outboxRepository;

    @Test
    @DisplayName("특정 상태인 Outbox 엔티티를 생성일 기준으로 정렬하여 조회")
    void findAllByOutboxStatusOrderByCreatedAt() {
        Outbox outbox1 = outboxRepository.save(new Outbox("Payment Failure", "Reservation Data"));
        Outbox outbox2 = outboxRepository.save(new Outbox("Payment Failure2", "Reservation Data"));
        Outbox outbox3 = outboxRepository.save(new Outbox("Payment Failure3", "Reservation Data"));
        outbox3.markCompleted();

        List<Outbox> allByStatusOrderByCreatedAt = outboxRepository.findAllByOutboxStatusOrderByCreatedAt(OutboxStatus.PENDING);

        assertThat(allByStatusOrderByCreatedAt)
                .containsExactlyInAnyOrder(outbox1, outbox2)
                .doesNotContain(outbox3);
    }
}
