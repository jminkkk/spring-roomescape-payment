package roomescape.payment.client.dto.request;

import roomescape.payment.client.PaymentClientType;

public record CancelPaymentRequest(
        PaymentClientType paymentClientType,
        String paymentKey,
        String cancelReason,
        Long cancelAmount
) {
}
