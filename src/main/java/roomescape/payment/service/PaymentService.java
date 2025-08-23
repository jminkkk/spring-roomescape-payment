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

    @Transactional(propagation = Propagation.REQUIRED)
    public Payment createPayment(final ConfirmPaymentRequest confirmPaymentRequest, final Reservation reservation) {
        PaymentClient paymentClient = paymentClients.get(confirmPaymentRequest.providerName());
        ConfirmPaymentResponseFromClient confirmPaymentResponseFromClient = paymentClient.confirm(
                confirmPaymentRequest);
        Payment payment = confirmPaymentResponseFromClient.toPayment(reservation);

        log.error("[ PaymentService] Reservation {} ", Thread.currentThread().getId());
        log.error("[ PaymentService] Payment {} ", TransactionSynchronizationManager.getCurrentTransactionName());

        // 커밋 실패 시 FailedPaymentRegister에 결제 정보 저장
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    log.info("롤백 감지, FailedPaymentRegister 호출");
                    failedPaymentRegister.registerFailPayment(payment, reservation);
                }
            }
        });

        paymentRepository.save(payment);
        return payment;
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
