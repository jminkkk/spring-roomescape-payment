package roomescape.payment.client.toss;

import roomescape.payment.client.exception.PaymentErrorType;

public enum TossErrorCode {

    // PROVIDER_ERROR
    PROVIDER_ERROR(PaymentErrorType.PROVIDER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
    CARD_PROCESSING_ERROR(PaymentErrorType.PROVIDER_ERROR, "카드사에서 오류가 발생했습니다."),
    FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING(PaymentErrorType.PROVIDER_ERROR, "결제가 완료되지 않았어요. 다시 시도해주세요."),
    FAILED_INTERNAL_SYSTEM_PROCESSING(PaymentErrorType.PROVIDER_ERROR, "내부 시스템 처리 작업이 실패했습니다."),
    UNKNOWN_PAYMENT_ERROR(PaymentErrorType.PROVIDER_ERROR, "결제에 실패했어요. 같은 문제가 반복된다면 은행이나 카드사로 문의해주세요."),

    // CLIENT_ERROR
    INVALID_API_KEY(PaymentErrorType.CLIENT_ERROR, "잘못된 시크릿키 연동 정보입니다."),
    INVALID_REQUEST(PaymentErrorType.CLIENT_ERROR, "잘못된 요청입니다."),
    FORBIDDEN_REQUEST(PaymentErrorType.CLIENT_ERROR, "허용되지 않은 요청입니다."),
    UNAUTHORIZED_KEY(PaymentErrorType.CLIENT_ERROR, "인증되지 않은 시크릿 키 혹은 클라이언트 키입니다."),
    INCORRECT_BASIC_AUTH_FORMAT(PaymentErrorType.CLIENT_ERROR, "잘못된 요청입니다. ':' 를 포함해 인코딩해주세요."),
    NOT_FOUND_TERMINAL_ID(PaymentErrorType.CLIENT_ERROR, "단말기번호(Terminal Id)가 없습니다. 토스페이먼츠로 문의 바랍니다."),
    INVALID_UNREGISTERED_SUBMALL(PaymentErrorType.CLIENT_ERROR, "등록되지 않은 서브몰입니다."),
    NOT_REGISTERED_BUSINESS(PaymentErrorType.CLIENT_ERROR, "등록되지 않은 사업자 번호입니다."),
    INVALID_AUTHORIZE_AUTH(PaymentErrorType.CLIENT_ERROR, "유효하지 않은 인증 방식입니다."),

    // USER_INPUT_ERROR
    INVALID_CARD_NUMBER(PaymentErrorType.USER_INPUT_ERROR, "카드번호를 다시 확인해주세요."),
    INVALID_CARD_EXPIRATION(PaymentErrorType.USER_INPUT_ERROR, "카드 정보를 다시 확인해주세요. (유효기간)"),
    INVALID_STOPPED_CARD(PaymentErrorType.USER_INPUT_ERROR, "정지된 카드입니다."),
    INVALID_CARD_LOST_OR_STOLEN(PaymentErrorType.USER_INPUT_ERROR, "분실 혹은 도난 카드입니다."),
    INVALID_REJECT_CARD(PaymentErrorType.USER_INPUT_ERROR, "카드 사용이 거절되었습니다. 카드사 문의가 필요합니다."),
    INVALID_PASSWORD(PaymentErrorType.USER_INPUT_ERROR, "결제 비밀번호가 일치하지 않습니다."),
    NOT_ALLOWED_POINT_USE(PaymentErrorType.USER_INPUT_ERROR, "포인트 사용이 불가한 카드입니다."),
    REJECT_CARD_PAYMENT(PaymentErrorType.USER_INPUT_ERROR, "한도초과 혹은 잔액부족으로 결제에 실패했습니다."),
    REJECT_CARD_COMPANY(PaymentErrorType.USER_INPUT_ERROR, "결제 승인이 거절되었습니다."),
    REJECT_ACCOUNT_PAYMENT(PaymentErrorType.USER_INPUT_ERROR, "잔액부족으로 결제에 실패했습니다."),
    REJECT_TOSSPAY_INVALID_ACCOUNT(PaymentErrorType.USER_INPUT_ERROR, "출금이체 등록이 되어 있지 않아요. 계좌를 다시 등록해 주세요."),
    INVALID_ACCOUNT_INFO_RE_REGISTER(PaymentErrorType.USER_INPUT_ERROR, "유효하지 않은 계좌입니다. 계좌 재등록 후 시도해주세요."),

    // POLICY_VIOLATION
    BELOW_MINIMUM_AMOUNT(PaymentErrorType.POLICY_VIOLATION, "결제 금액이 최소 금액 미만입니다."),
    EXCEED_MAX_AMOUNT(PaymentErrorType.POLICY_VIOLATION, "거래금액 한도를 초과했습니다."),
    EXCEED_MAX_PAYMENT_AMOUNT(PaymentErrorType.POLICY_VIOLATION, "하루 결제 가능 금액을 초과했습니다."),
    EXCEED_MAX_DAILY_PAYMENT_COUNT(PaymentErrorType.POLICY_VIOLATION, "하루 결제 가능 횟수를 초과했습니다."),
    EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT(PaymentErrorType.POLICY_VIOLATION, "당월 결제 가능금액을 초과하셨습니다."),
    EXCEED_MAX_ONE_DAY_AMOUNT(PaymentErrorType.POLICY_VIOLATION, "일일 한도를 초과했습니다."),
    EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT(PaymentErrorType.POLICY_VIOLATION, "1일 출금 한도를 초과했습니다."),
    EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT(PaymentErrorType.POLICY_VIOLATION, "1회 출금 한도를 초과했습니다."),
    EXCEED_MAX_AUTH_COUNT(PaymentErrorType.POLICY_VIOLATION, "최대 인증 횟수를 초과했습니다. 카드사로 문의해주세요."),
    EXCEED_MAX_CARD_INSTALLMENT_PLAN(PaymentErrorType.POLICY_VIOLATION, "설정 가능한 최대 할부 개월 수를 초과했습니다."),
    INVALID_CARD_INSTALLMENT_PLAN(PaymentErrorType.POLICY_VIOLATION, "할부 개월 정보가 잘못되었습니다."),
    NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT(PaymentErrorType.POLICY_VIOLATION, "할부가 지원되지 않는 카드 또는 가맹점입니다."),
    NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN(PaymentErrorType.POLICY_VIOLATION, "할부가 지원되지 않는 카드입니다."),
    NOT_AVAILABLE_PAYMENT(PaymentErrorType.POLICY_VIOLATION, "결제가 불가능한 시간대입니다."),
    NOT_AVAILABLE_BANK(PaymentErrorType.POLICY_VIOLATION, "은행 서비스 시간이 아닙니다."),
    RESTRICTED_TRANSFER_ACCOUNT(PaymentErrorType.POLICY_VIOLATION, "계좌는 등록 후 12시간 뒤부터 결제할 수 있습니다."),
    FDS_ERROR(PaymentErrorType.POLICY_VIOLATION, "위험거래가 감지되어 결제가 제한됩니다."),

    //  STATE_FINAL
    ALREADY_PROCESSED_PAYMENT(PaymentErrorType.STATE_FINAL, "이미 처리된 결제입니다."),
    NOT_FOUND_PAYMENT(PaymentErrorType.STATE_FINAL, "존재하지 않는 결제 정보입니다."),
    NOT_FOUND_PAYMENT_SESSION(PaymentErrorType.STATE_FINAL, "결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다."),

    // STATE_TEMPORARY
    UNAPPROVED_ORDER_ID(PaymentErrorType.STATE_TEMPORARY, "아직 승인되지 않은 주문번호입니다.");

    private final PaymentErrorType type;
    private final String message;

    TossErrorCode(PaymentErrorType type, String message) {
        this.type = type;
        this.message = message;
    }

    public PaymentErrorType getType() { return type; }
    public String getMessage() { return message; }

    public boolean isRetryable() {
        return type.isRetryable();
    }

    public boolean isCircuitBreakerFailure() {
        return type.isCircuitBreakerFailure();
    }

    public boolean isNotForUser() {
        return type == PaymentErrorType.CLIENT_ERROR || type == PaymentErrorType.INFRASTRUCTURE;
    }

    public static TossErrorCode fromCode(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException e) {
            return UNKNOWN_PAYMENT_ERROR;
        }
    }
}
