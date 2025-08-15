package roomescape.payment.client.dto.response;

public record CancelPaymentResponseFromClient(
        String mId,
        String paymentKey,
        String orderId,
        String orderName,
        String status,
        Long totalAmount
) {
}
