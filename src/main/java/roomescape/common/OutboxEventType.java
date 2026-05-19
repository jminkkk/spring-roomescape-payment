package roomescape.common;

public enum OutboxEventType {
    RESERVATION_PAYMENT("RESERVATION_PAYMENT"),
    EMAIL_SEND_FAILED("EMAIL_SEND_FAILED");

    private final String type;

    OutboxEventType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
