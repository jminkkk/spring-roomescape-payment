package roomescape.payment.client.exception;

public enum PaymentErrorType {
    INFRASTRUCTURE(true, true, "네트워크/인프라 오류"),      // 서킷브레이커 대상, 재시도 가능
    PROVIDER_ERROR(true, true, "PG사 장애 또는 내부 시스템 오류"), // 서킷브레이커 대상, 재시도 가능
    CLIENT_ERROR(false, false, "API 요청/구성 오류"),        // 재시도 불가, 개발자 책임
    USER_INPUT_ERROR(false, false, "사용자 카드/계좌 문제"), // 재시도 불가, 사용자 조치 필요
    POLICY_VIOLATION(false, false, "한도/정책 위반"),        // 재시도 불가, 사용자 조치
    STATE_TEMPORARY(false, true, "일시적 상태 불일치"),      // 재시도 가능 (예: 승인 대기)
    STATE_FINAL(false, false, "영구 상태 오류"),             // 재시도 불가 (예: 이미 처리됨)
    ;

    private final boolean retryable;
    private final boolean circuitBreakerFailure;
    private final String description;

    PaymentErrorType(boolean retryable, boolean circuitBreakerFailure, String description) {
        this.retryable = retryable;
        this.circuitBreakerFailure = circuitBreakerFailure;
        this.description = description;
    }

    public boolean isRetryable() { return retryable; }
    public boolean isCircuitBreakerFailure() { return circuitBreakerFailure; }
    public String getDescription() { return description; }
}
