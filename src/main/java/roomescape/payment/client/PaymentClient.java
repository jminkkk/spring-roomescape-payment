package roomescape.payment.client;

import roomescape.payment.client.dto.ConfirmPaymentRequest;
import roomescape.payment.model.PaymentInfoFromClient;

public interface PaymentClient {

    PaymentInfoFromClient confirm(ConfirmPaymentRequest confirmPaymentRequest);
}
