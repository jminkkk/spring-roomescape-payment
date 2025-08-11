package roomescape.payment.client.dto.request;

import roomescape.payment.client.PaymentClientType;
import roomescape.reservation.dto.request.CreateMyReservationRequest;

public record ConfirmPaymentRequest(
        PaymentClientType providerName,
        String paymentKey,
        String orderId,
        Long amount) {
    public static ConfirmPaymentRequest from(final CreateMyReservationRequest createMyReservationRequest) {
        return new ConfirmPaymentRequest(
                PaymentClientType.of(createMyReservationRequest.providerName()),
                createMyReservationRequest.paymentKey(),
                createMyReservationRequest.orderId(),
                createMyReservationRequest.amount());
    }
}
