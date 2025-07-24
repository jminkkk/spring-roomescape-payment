package roomescape.payment.client.exception;

public class PaymentBusinessException extends PaymentException {
    public PaymentBusinessException(String message) {
        super(PaymentErrorType.USER_INPUT_ERROR, message);
    }
}

