package roomescape.payment.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import roomescape.payment.client.PaymentClient;
import roomescape.payment.client.PaymentClientStatus;
import roomescape.payment.client.PaymentClientType;
import roomescape.payment.client.dto.ConfirmPaymentRequest;
import roomescape.payment.model.Payment;
import roomescape.payment.model.PaymentInfoFromClient;
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
        PaymentInfoFromClient paymentInfoFromClient = paymentClient.confirm(confirmPaymentRequest, generateIdempotencyKey(reservation.getId()));
        Payment payment = paymentInfoFromClient.toPayment(reservation);
        paymentRepository.save(payment);
    }

    private String generateIdempotencyKey(Long reservationId) {
        return "reservation_" + reservationId;
    }

    public Map<PaymentClientType, PaymentClientStatus> getPaymentClientStatuses() {
        return Arrays.stream(PaymentClientType.values())
                .collect(Collectors.toMap(clientType -> clientType, clientType -> PaymentClientStatus.of(isClientAvailable(paymentClients.get(clientType)))));
    }

    private boolean isClientAvailable(PaymentClient client) {
        try {
            PaymentClientType paymentProvider = client.getPaymentProvider();
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(paymentProvider.name().toLowerCase() + "-payment");
            return circuitBreaker.getState() == CircuitBreaker.State.CLOSED;
        } catch (Exception e) {
            return true; // 조회 실패시 사용 가능한 것으로 간주
        }
    }
}
