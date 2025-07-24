package roomescape.payment.client.exception;

public class PaymentClientException extends PaymentException {
    public PaymentClientException(String message) {
        super(PaymentErrorType.CLIENT_ERROR, message);
    }
}
