package roomescape.payment.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import roomescape.payment.client.PaymentClient;
import roomescape.payment.client.PaymentClientStatus;
import roomescape.payment.client.PaymentClientType;
import roomescape.payment.client.dto.request.ConfirmPaymentRequest;
import roomescape.payment.client.dto.response.ConfirmPaymentResponseFromClient;
import roomescape.payment.model.Payment;
import roomescape.payment.outbox.FailedPaymentRegister;
import roomescape.payment.repository.PaymentRepository;
import roomescape.reservation.dto.request.CreateMyReservationRequest;
import roomescape.reservation.model.Reservation;

@Service
public class PaymentService {

    private final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepository;
    private final FailedPaymentRegister failedPaymentRegister;
    private final Map<PaymentClientType, PaymentClient> paymentClients;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public PaymentService(final PaymentRepository paymentRepository,
            final FailedPaymentRegister failedPaymentRegister,
            final List<PaymentClient> clientList,
            final CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        this.paymentRepository = paymentRepository;
        this.failedPaymentRegister = failedPaymentRegister;
        this.paymentClients = clientList.stream()
                .collect(Collectors.toMap(PaymentClient::getPaymentProvider, c -> c));
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public ConfirmPaymentResponseFromClient callPG(final ConfirmPaymentRequest confirmPaymentRequest) {
        PaymentClient paymentClient = paymentClients.get(confirmPaymentRequest.providerName());
        return paymentClient.confirm(confirmPaymentRequest);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Payment createInProgressPayment(final CreateMyReservationRequest request, final Reservation reservation) {
        Payment payment = Payment.inProgress(request.paymentKey(), request.orderId(), request.amount(), reservation);
        return paymentRepository.save(payment);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Payment completePayment(final Reservation reservation) {
        Payment payment = paymentRepository.findByReservation(reservation)
                .orElseThrow(() -> new IllegalStateException("결제 정보를 찾을 수 없습니다. reservationId=" + reservation.getId()));

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    log.info("롤백 감지, FailedPaymentRegister 호출");
                    failedPaymentRegister.registerFailPayment(payment, reservation);
                }
            }
        });

        payment.complete();
        return paymentRepository.save(payment);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteInProgressPayment(final Reservation reservation) {
        paymentRepository.findByReservation(reservation)
                .ifPresent(paymentRepository::delete);
    }

    public Map<PaymentClientType, PaymentClientStatus> getPaymentClientStatuses() {
        return Arrays.stream(PaymentClientType.values())
                .collect(Collectors.toMap(clientType -> clientType,
                        clientType -> PaymentClientStatus.of(isClientAvailable(paymentClients.get(clientType)))));
    }

    private boolean isClientAvailable(PaymentClient client) {
        PaymentClientType paymentProvider = client.getPaymentProvider();
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(
                paymentProvider.name().toLowerCase() + "-payment");
        return circuitBreaker.getState() == CircuitBreaker.State.CLOSED;
    }
}
