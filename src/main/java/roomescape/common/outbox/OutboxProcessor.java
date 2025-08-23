package roomescape.common.outbox;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import roomescape.payment.outbox.ReservationPaymentEvent;
import roomescape.payment.repository.PaymentRepository;
import roomescape.reservation.repository.ReservationRepository;

@Component
public class OutboxProcessor {

    private final OutboxRepository outboxRepository;
    private final OutboxMapper outboxMapper;
    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;

    public OutboxProcessor(final OutboxRepository outboxRepository, final OutboxMapper outboxMapper, final PaymentRepository paymentRepository, final ReservationRepository reservationRepository) {
        this.outboxRepository = outboxRepository;
        this.outboxMapper = outboxMapper;
        this.paymentRepository = paymentRepository;
        this.reservationRepository = reservationRepository;
    }

    @Scheduled(fixedDelay = 60000)
    public void retryFailedPayments() {
        List<Outbox> failedLogs = outboxRepository.findAllByOutboxStatusOrderByCreatedAt(OutboxStatus.PENDING);
        failedLogs.forEach(this::processFailedPayments);
    }

    public void processFailedPayments(Outbox outbox) {
        ReservationPaymentEvent reservationPaymentEvent = outboxMapper.toDomain(outbox, ReservationPaymentEvent.class);
        try {
            reservationRepository.save(reservationPaymentEvent.reservation());
            paymentRepository.save(reservationPaymentEvent.payment());
            outbox.markCompleted();
        } catch (Exception e) {
            outbox.markFailed();
        }
    }
}
