package roomescape.payment.client;

public enum PaymentClientStatus {
    AVAILABLE("Available"),
    UNAVAILABLE("Unavailable"),
    ;

    private final String status;

    PaymentClientStatus(String status) {
        this.status = status;
    }

    public String getStatus() {
        return status;
    }

    public static PaymentClientStatus of(boolean isProviderAvailable) {
        return isProviderAvailable ? AVAILABLE : UNAVAILABLE;
    }

    public  boolean isAvailable() {
        return this == AVAILABLE;
    }
}
