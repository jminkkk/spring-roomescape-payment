package roomescape.payment.client.toss;

import roomescape.payment.client.dto.request.CancelPaymentRequest;

public record TossCancelRequest(
        String paymentKey,
        String cancelReason,
        Long cancelAmount
) {
    public static TossCancelRequest from(final CancelPaymentRequest cancelPaymentRequest) {
        return new TossCancelRequest(
                cancelPaymentRequest.paymentKey(),
                cancelPaymentRequest.cancelReason(),
                cancelPaymentRequest.cancelAmount()
        );
    }
}
