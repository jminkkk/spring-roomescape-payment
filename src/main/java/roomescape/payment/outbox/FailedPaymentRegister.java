package roomescape.payment.outbox;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import roomescape.common.outbox.Outbox;
import roomescape.common.outbox.OutboxMapper;
import roomescape.common.outbox.OutboxRepository;
import roomescape.payment.model.Payment;
import roomescape.reservation.model.Reservation;

@Component
public class FailedPaymentRegister {

    private static final Logger log = LoggerFactory.getLogger(FailedPaymentRegister.class);

    private final OutboxRepository outboxRepository;
    private final OutboxMapper outboxMapper;

    public FailedPaymentRegister(final OutboxRepository outboxRepository, final OutboxMapper outboxMapper) {
        this.outboxRepository = outboxRepository;
        this.outboxMapper = outboxMapper;
    }

    @Transactional(propagation = REQUIRES_NEW)
    public void registerFailPayment(final Payment payment, final Reservation reservation) {
        log.error("[ PaymentService - Rollback] Reservation {} ", Thread.currentThread().getId());
        log.error("[ PaymentService - Rollback] Payment {} ", TransactionSynchronizationManager.getCurrentTransactionName());

        ReservationPaymentEvent reservationPaymentEvent = new ReservationPaymentEvent(reservation, payment);
        Outbox outbox = outboxMapper.toOutboxEvent(reservationPaymentEvent);
        outboxRepository.save(outbox);
    }
}
