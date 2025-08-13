package roomescape.payment.client.toss;

import roomescape.payment.client.dto.request.ConfirmPaymentRequest;

public record TossConfirmRequest(
        String paymentKey,
        String orderId,
        Long amount
) {
    public static TossConfirmRequest from(ConfirmPaymentRequest confirmPaymentRequest) {
        return new TossConfirmRequest(
                confirmPaymentRequest.paymentKey(),
                confirmPaymentRequest.orderId(),
                confirmPaymentRequest.amount()
        );
    }
}
