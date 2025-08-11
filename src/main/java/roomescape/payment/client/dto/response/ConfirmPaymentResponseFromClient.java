package roomescape.payment.client.dto.response;

import roomescape.payment.model.Payment;
import roomescape.reservation.model.Reservation;

public record ConfirmPaymentResponseFromClient(String paymentKey,
                                    String orderId,
                                    Long totalAmount) {
    public Payment toPayment(final Reservation reservation) {
        return new Payment(paymentKey, orderId, totalAmount, reservation);
    }
}
