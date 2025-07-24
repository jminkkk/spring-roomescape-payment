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
}
