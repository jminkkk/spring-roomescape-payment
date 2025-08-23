package roomescape.payment.client.idempotency;

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import roomescape.payment.client.dto.response.ConfirmPaymentResponseFromClient;

@Component
public class IdempotencyInterceptor implements ClientHttpRequestInterceptor {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final CacheableIdempotencyService cacheableIdempotencyService;
    private final ObjectMapper objectMapper;

    public IdempotencyInterceptor(CacheableIdempotencyService cacheableIdempotencyService, ObjectMapper objectMapper) {
        this.cacheableIdempotencyService = cacheableIdempotencyService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String idempotencyKey = request.getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return execution.execute(request, body);
        }

        ConfirmPaymentResponseFromClient response = cacheableIdempotencyService.getOrCallPg(idempotencyKey, () -> {
            try {
                ClientHttpResponse resp = execution.execute(request, body);
                byte[] responseBody = resp.getBody().readAllBytes();
                return objectMapper.readValue(responseBody, ConfirmPaymentResponseFromClient.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        byte[] responseBody = objectMapper.writeValueAsBytes(response);
        return ByteArrayClientHttpResponse.from(responseBody, HttpStatusCode.valueOf(200));
    }
}
