package roomescape.payment.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import roomescape.payment.client.PaymentClient;
import roomescape.payment.client.PaymentClientStatus;
import roomescape.payment.client.PaymentClientType;
import roomescape.payment.client.dto.request.CancelPaymentRequest;
import roomescape.payment.client.dto.request.ConfirmPaymentRequest;
import roomescape.payment.client.dto.response.CancelPaymentResponseFromClient;
import roomescape.payment.client.dto.response.ConfirmPaymentResponseFromClient;
import roomescape.payment.model.Payment;
import roomescape.payment.repository.PaymentRepository;
import roomescape.reservation.model.Reservation;

@Service
public class PaymentService {

    private static final String SAVE_PAYMENT_FAILURE_MESSAGE = "저장 실패로 인한 결제 취소";
    private final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepository;
    private final Map<PaymentClientType, PaymentClient> paymentClients;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public PaymentService(final PaymentRepository paymentRepository, final List<PaymentClient> clientList, final CircuitBreakerRegistry circuitBreakerRegistry ) {
        this.paymentRepository = paymentRepository;
        this.paymentClients = clientList.stream()
                .collect(Collectors.toMap(PaymentClient::getPaymentProvider, c -> c));
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public Payment createPayment(final ConfirmPaymentRequest confirmPaymentRequest, final Reservation reservation) {
        PaymentClient paymentClient = paymentClients.get(confirmPaymentRequest.providerName());
        ConfirmPaymentResponseFromClient confirmPaymentResponseFromClient = paymentClient.confirm(confirmPaymentRequest);
        Payment payment = confirmPaymentResponseFromClient.toPayment(reservation);

        log.error("[ PaymentService] Reservation {} ", Thread.currentThread().getId());
        log.error("[ PaymentService] Payment {} ", TransactionSynchronizationManager.getCurrentTransactionName());
        return savePayment(paymentClient, payment);
    }

    public Payment savePayment(final PaymentClient paymentClient, final Payment payment) {
        try {
            paymentRepository.save(payment);
            throw new Exception();
        } catch (Exception e) {
            log.error("Error saving payment {}", payment);
            log.error("[ PaymentService - Rollback] Reservation {} ", Thread.currentThread().getId());
            log.error("[ PaymentService - Rollback] Payment {} ", TransactionSynchronizationManager.getCurrentTransactionName());
            CancelPaymentRequest cancelPaymentRequest = new CancelPaymentRequest(paymentClient.getPaymentProvider(), payment.getPaymentKey(), SAVE_PAYMENT_FAILURE_MESSAGE, payment.getAmount());
            CancelPaymentResponseFromClient cancelPaymentResponseFromClient = paymentClient.cancel(cancelPaymentRequest);
            log.error("[ PaymentService - Rollback] Reservation 취소 요청 성공 {}", cancelPaymentResponseFromClient);
        }
        throw new IllegalStateException("결제 저장에 실패했습니다. 결제를 취소했습니다."); 
    }

    public Map<PaymentClientType, PaymentClientStatus> getPaymentClientStatuses() {
        return Arrays.stream(PaymentClientType.values())
                .collect(Collectors.toMap(clientType -> clientType, clientType -> PaymentClientStatus.of(isClientAvailable(paymentClients.get(clientType)))));
    }

    private boolean isClientAvailable(PaymentClient client) {
            PaymentClientType paymentProvider = client.getPaymentProvider();
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(paymentProvider.name().toLowerCase() + "-payment");
            return circuitBreaker.getState() == CircuitBreaker.State.CLOSED;
    }
}
