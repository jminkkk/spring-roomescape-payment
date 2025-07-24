package roomescape.payment.client.exception;

public class PaymentStateException extends PaymentException {
    public PaymentStateException(String message, boolean temporary) {
        super(temporary ? PaymentErrorType.STATE_TEMPORARY : PaymentErrorType.STATE_FINAL, message);
    }
}
