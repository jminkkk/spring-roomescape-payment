package roomescape.payment.client.exception;

public abstract class PaymentException extends RuntimeException {
    private final PaymentErrorType type;

    public PaymentException(PaymentErrorType type, String message) {
        super(message);
        this.type = type;
    }

    public PaymentErrorType getType() {
        return type;
    }

    public boolean isRetryable() {
        return type.isRetryable();
    }

    public boolean isCircuitBreakerFailure() {
        return type.isCircuitBreakerFailure();
    }
}
