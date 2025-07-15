package roomescape.payment.client.toss;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import roomescape.payment.client.PaymentClient;
import roomescape.payment.client.dto.ConfirmPaymentRequest;
import roomescape.payment.model.PaymentInfoFromClient;


@Component
public class TossPaymentClient extends PaymentClient {

    private final RestClient restClient;

    public TossPaymentClient(final RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public String getProviderName() {
        return "Toss";
    }

    @Override
    public PaymentInfoFromClient confirm(ConfirmPaymentRequest confirmPaymentRequest, String idempotencyKey) {
        return restClient.post()
                .uri("/confirm")
                .body(confirmPaymentRequest)
                .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .retrieve()
                .toEntity(PaymentInfoFromClient.class)
                .getBody();
    }
}
