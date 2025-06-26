package roomescape.payment.client.dto;

import roomescape.reservation.dto.request.CreateMyReservationRequest;

public record ConfirmPaymentRequest(String paymentKey,
                                    String orderId,
                                    Long amount) {
    public static ConfirmPaymentRequest from(final CreateMyReservationRequest createMyReservationRequest) {
        return new ConfirmPaymentRequest(
                createMyReservationRequest.paymentKey(),
                createMyReservationRequest.orderId(),
                createMyReservationRequest.amount());
    }
}
