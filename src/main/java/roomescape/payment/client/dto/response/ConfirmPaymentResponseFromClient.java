package roomescape.payment.client.dto.response;

public record ConfirmPaymentResponseFromClient(String paymentKey,
                                    String orderId,
                                    Long totalAmount) {
}
