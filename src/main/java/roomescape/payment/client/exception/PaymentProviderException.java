package roomescape.payment.client.exception;

public class PaymentProviderException extends PaymentException {
    public PaymentProviderException(String message) {
        super(PaymentErrorType.PROVIDER_ERROR, message);
    }
}
