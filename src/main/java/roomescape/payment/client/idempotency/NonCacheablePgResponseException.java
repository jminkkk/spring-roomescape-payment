package roomescape.payment.client.idempotency;

import org.springframework.http.HttpStatusCode;

/**
 * 캐싱하면 안 되는 PG 응답을 담아 전달한다.
 * {@code @Cacheable}은 예외가 발생하면 결과를 캐싱하지 않으므로, 실패 응답이 캐시에 남지 않도록 하기 위해 사용한다.
 */
public class NonCacheablePgResponseException extends RuntimeException {

    private final transient HttpStatusCode statusCode;
    private final byte[] body;

    public NonCacheablePgResponseException(final HttpStatusCode statusCode, final byte[] body) {
        // 흐름 제어 목적이므로 스택 트레이스를 채우지 않는다.
        super(null, null, false, false);
        this.statusCode = statusCode;
        this.body = body;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    public byte[] getBody() {
        return body;
    }
}
