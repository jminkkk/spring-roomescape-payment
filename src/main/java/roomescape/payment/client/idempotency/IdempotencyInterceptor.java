package roomescape.payment.client.idempotency;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyInterceptor implements ClientHttpRequestInterceptor {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final CacheableIdempotencyService cacheableIdempotencyService;

    public IdempotencyInterceptor(CacheableIdempotencyService cacheableIdempotencyService) {
        this.cacheableIdempotencyService = cacheableIdempotencyService;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String idempotencyKey = request.getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return execution.execute(request, body);
        }

        try {
            CachedPaymentResponse response = cacheableIdempotencyService.getOrCallPg(idempotencyKey,
                    () -> callPg(request, body, execution));
            return ByteArrayClientHttpResponse.from(response.body(), HttpStatusCode.valueOf(response.statusCode()));
        } catch (NonCacheablePgResponseException e) {
            // 실패 응답은 캐싱하지 않되, 상태 코드와 본문은 그대로 상태 핸들러에 전달한다.
            return ByteArrayClientHttpResponse.from(e.getBody(), e.getStatusCode());
        } catch (UncheckedIOException e) {
            // 통신 자체가 실패한 경우 원래의 IOException으로 되돌려 RestClient가 판단하도록 한다.
            throw e.getCause();
        }
    }

    private CachedPaymentResponse callPg(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) {
        try (ClientHttpResponse response = execution.execute(request, body)) {
            HttpStatusCode statusCode = response.getStatusCode();
            byte[] responseBody = response.getBody().readAllBytes();

            if (!statusCode.is2xxSuccessful()) {
                throw new NonCacheablePgResponseException(statusCode, responseBody);
            }
            return new CachedPaymentResponse(statusCode.value(), responseBody);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
