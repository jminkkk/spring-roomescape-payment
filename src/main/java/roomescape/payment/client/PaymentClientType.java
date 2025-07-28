package roomescape.payment.client;

public enum PaymentClientType {
    TOSS("Toss"),
    KAKAO("KakaoPay"),
    NAVER("NaverPay"),
    ;
    private final String displayName;

    PaymentClientType(String displayName) {
        this.displayName = displayName;
    }

    public static PaymentClientType of(String displayName) {
        for (PaymentClientType type : values()) {
            if (type.displayName.equalsIgnoreCase(displayName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("제공되지 않는 PG 사입니다.  " + displayName);
    }
}
