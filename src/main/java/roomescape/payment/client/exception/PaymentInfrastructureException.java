package roomescape.payment.client.exception;

public class PaymentInfrastructureException extends PaymentException {
    public PaymentInfrastructureException(String message, String errorCode) {
        super(message, errorCode, true);
    }
}
