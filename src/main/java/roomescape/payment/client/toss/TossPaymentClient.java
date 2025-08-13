package roomescape.payment.client.toss;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import roomescape.payment.client.PaymentClient;
import roomescape.payment.client.PaymentClientType;
import roomescape.payment.client.dto.ConfirmPaymentRequest;
import roomescape.payment.model.PaymentInfoFromClient;


@Component
public class TossPaymentClient extends PaymentClient {

    private final RestClient restClient;

    public TossPaymentClient(final RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public PaymentClientType getPaymentProvider() {
        return PaymentClientType.TOSS;
    }

    @Override
    @CircuitBreaker(name = "toss-payment", fallbackMethod = "fallbackConfirm")
    public ConfirmPaymentResponseFromClient confirm(ConfirmPaymentRequest confirmPaymentRequest) {
        TossConfirmRequest tossConfirmRequest = TossConfirmRequest.from(confirmPaymentRequest);

        return restClient.post()
                .uri("/confirm")
                .body(tossConfirmRequest)
                .header(IDEMPOTENCY_KEY_HEADER, confirmPaymentRequest.paymentKey())
                .retrieve()
                .toEntity(PaymentInfoFromClient.class)
                .getBody();
    }
}
