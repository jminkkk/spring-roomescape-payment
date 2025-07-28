package roomescape.payment.client.exception;

public class PaymentInfrastructureException extends PaymentException {
    public PaymentInfrastructureException(String message) {
        super(PaymentErrorType.INFRASTRUCTURE, message);
    }
}
