package roomescape.payment.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import roomescape.payment.client.PaymentClient;
import roomescape.payment.client.PaymentClientStatus;
import roomescape.payment.client.PaymentClientType;
import roomescape.payment.client.dto.request.ConfirmPaymentRequest;
import roomescape.payment.model.Payment;
import roomescape.payment.client.dto.response.ConfirmPaymentResponseFromClient;
import roomescape.payment.repository.PaymentRepository;
import roomescape.reservation.model.Reservation;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final Map<PaymentClientType, PaymentClient> paymentClients;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public PaymentService(final PaymentRepository paymentRepository, final List<PaymentClient> clientList, final CircuitBreakerRegistry circuitBreakerRegistry ) {
        this.paymentRepository = paymentRepository;
        this.paymentClients = clientList.stream()
                .collect(Collectors.toMap(PaymentClient::getPaymentProvider, c -> c));
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public void createPayment(final ConfirmPaymentRequest confirmPaymentRequest, final Reservation reservation) {
        PaymentClient paymentClient = paymentClients.get(confirmPaymentRequest.providerName());
        ConfirmPaymentResponseFromClient confirmPaymentResponseFromClient = paymentClient.confirm(confirmPaymentRequest);
        Payment payment = confirmPaymentResponseFromClient.toPayment(reservation);
        savePayment(payment);
    }

    // 3회 재시도 처리
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public void savePayment(final Payment payment) {
        paymentRepository.save(payment);
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
