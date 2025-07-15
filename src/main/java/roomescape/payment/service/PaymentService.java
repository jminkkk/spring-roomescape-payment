package roomescape.payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import roomescape.payment.client.PaymentClient;
import roomescape.payment.client.dto.ConfirmPaymentRequest;
import roomescape.payment.model.Payment;
import roomescape.payment.model.PaymentInfoFromClient;
import roomescape.payment.repository.PaymentRepository;
import roomescape.reservation.model.Reservation;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentClient paymentClient;

    public PaymentService(final PaymentRepository paymentRepository, final PaymentClient paymentClient) {
        this.paymentRepository = paymentRepository;
        this.paymentClient = paymentClient;
    }

    public void createPayment(final ConfirmPaymentRequest confirmPaymentRequest, final Reservation reservation) {
        System.out.println("Payment " + Thread.currentThread().getId());
        System.out.println("Payment " + TransactionSynchronizationManager.getCurrentTransactionName());

        PaymentInfoFromClient paymentInfoFromClient = paymentClient.confirm(confirmPaymentRequest, generateIdempotencyKey(reservation.getId()));
        Payment payment = paymentInfoFromClient.toPayment(reservation);
        paymentRepository.save(payment);
    }

    private String generateIdempotencyKey(Long reservationId) {
        return "reservation_" + reservationId;
    }
}
