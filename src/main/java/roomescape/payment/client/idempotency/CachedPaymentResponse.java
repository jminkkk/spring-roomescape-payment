package roomescape.payment.client.idempotency;

/**
 * 멱등키 단위로 캐싱하는 PG 응답이다.
 * 특정 DTO로 역직렬화하지 않고 상태 코드와 본문을 그대로 보관하여, 호출한 API의 응답 타입에 의존하지 않는다.
 */
public record CachedPaymentResponse(int statusCode, byte[] body) {
}
