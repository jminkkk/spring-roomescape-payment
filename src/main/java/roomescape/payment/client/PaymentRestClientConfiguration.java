package roomescape.payment.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.classic.AIMDBackoffManager;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.impl.DefaultConnectionReuseStrategy;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import roomescape.payment.client.idempotency.IdempotencyInterceptor;
import roomescape.payment.client.idempotency.IdempotencyKeyRetryStrategy;
import roomescape.payment.client.toss.TossPaymentErrorHandler;

@Configuration
public class PaymentRestClientConfiguration {

    private static final String BASIC_PREFIX = "Basic ";

    private final PaymentProperties paymentProperties;
    private final IdempotencyInterceptor idempotencyInterceptor;
    private final TossPaymentErrorHandler tossPaymentErrorHandler;

    public PaymentRestClientConfiguration(final PaymentProperties paymentProperties, final IdempotencyInterceptor idempotencyInterceptor,
            TossPaymentErrorHandler tossPaymentErrorHandler
    ) {
        this.paymentProperties = paymentProperties;
        this.idempotencyInterceptor = idempotencyInterceptor;
        this.tossPaymentErrorHandler = tossPaymentErrorHandler;
    }

    @Bean
    public RestClient tossRestClient() {
        return restClient()
                .baseUrl("https://api.tosspayments.com/v1/payments")
                .defaultHeader(HttpHeaders.AUTHORIZATION, getClientAuthorizationValue())
                .requestInterceptor(idempotencyInterceptor)
                .defaultStatusHandler(tossPaymentErrorHandler)
                .build();
    }

    private String getClientAuthorizationValue() {
        byte[] encodedBytes = Base64.getEncoder()
                .encode((paymentProperties.getSecretKey() + paymentProperties.getPassword())
                        .getBytes(StandardCharsets.UTF_8));

        return BASIC_PREFIX + new String(encodedBytes);
    }

    @Bean
    public RestClient.Builder restClient() {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient()));
    }

    public HttpClient httpClient() {
        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig())
                .setConnectionManager(connectionManager())
                .setBackoffManager(new AIMDBackoffManager(connectionManager()))
                .setRetryStrategy(IdempotencyKeyRetryStrategy.INSTANCE)
                .setConnectionReuseStrategy(DefaultConnectionReuseStrategy.INSTANCE)
                .setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy())
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }

    // 요청별로 적용되며 요청 생명주기에 대한 타임아웃을 정의
    private RequestConfig requestConfig() {
        return RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(1))
                .setResponseTimeout(Timeout.ofMinutes(1))
                .build();
    }

    // 클라이언트 커넥션 풀에 대해 전체 풀링 동작을 설정, 기본 커넥션/소켓 구성을 적용
    private PoolingHttpClientConnectionManager connectionManager() {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(200)
                .setMaxConnPerRoute(50)
                .setConnPoolPolicy(PoolReusePolicy.LIFO)
                .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
                .setDefaultConnectionConfig(connectionConfig())
                .setDefaultSocketConfig(socketConfig())
                .build();
    }

    private ConnectionConfig connectionConfig() {
        return ConnectionConfig.custom()
                .setTimeToLive(TimeValue.ofMinutes(1))
                .setConnectTimeout(Timeout.ofSeconds(1))
                .setValidateAfterInactivity(TimeValue.ofMinutes(1))
                .build();
    }

    private SocketConfig socketConfig() {
        return SocketConfig.custom()
                .setSoTimeout(Timeout.ofMinutes(1))
                .build();
    }
}
