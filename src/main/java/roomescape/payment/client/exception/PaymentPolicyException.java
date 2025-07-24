package roomescape.payment.client.exception;

public class PaymentPolicyException extends PaymentException {
    public PaymentPolicyException(String message) {
        super(PaymentErrorType.POLICY_VIOLATION, message);
    }
}

