package roomescape.payment.client.toss;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import roomescape.payment.client.PaymentClient;
import roomescape.payment.client.PaymentClientType;
import roomescape.payment.client.dto.request.ConfirmPaymentRequest;
import roomescape.payment.client.dto.response.CancelPaymentResponseFromClient;
import roomescape.payment.client.dto.request.CancelPaymentRequest;
import roomescape.payment.client.dto.response.ConfirmPaymentResponseFromClient;


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
                .toEntity(ConfirmPaymentResponseFromClient.class)
                .getBody();
    }

    @Override
    public CancelPaymentResponseFromClient cancel(CancelPaymentRequest cancelPaymentRequest) {
        TossCancelRequest tossCancelRequest = TossCancelRequest.from(cancelPaymentRequest);

        return restClient.post()
                .uri("/"+ cancelPaymentRequest.paymentKey() + "/cancel")
                .body(tossCancelRequest)
                .header(IDEMPOTENCY_KEY_HEADER, cancelPaymentRequest.paymentKey())
                .retrieve()
                .toEntity(CancelPaymentResponseFromClient.class)
                .getBody();
    }
}
