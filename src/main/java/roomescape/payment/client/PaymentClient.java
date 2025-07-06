package roomescape.payment.client;

import roomescape.payment.client.dto.ConfirmPaymentRequest;
import roomescape.payment.model.PaymentInfoFromClient;

public abstract class PaymentClient {

    public abstract PaymentInfoFromClient confirm(ConfirmPaymentRequest confirmPaymentRequest, String idempotencyKey);
}
