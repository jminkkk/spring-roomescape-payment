package roomescape.payment.client.idempotency;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import roomescape.payment.client.PaymentClient;
import roomescape.payment.client.PaymentClientType;
import roomescape.payment.client.dto.request.ConfirmPaymentRequest;
import roomescape.payment.client.dto.response.ConfirmPaymentResponseFromClient;
import roomescape.payment.client.exception.PaymentBusinessException;
import roomescape.payment.client.exception.PaymentProviderException;
import roomescape.payment.client.toss.TossPaymentClient;
import roomescape.payment.client.toss.TossPaymentErrorHandler;

@DisplayName("멱등성 인터셉터는 PG 응답의 상태 코드를 보존한다")
class IdempotencyInterceptorTest {

    private static final ConfirmPaymentRequest REQUEST =
            new ConfirmPaymentRequest(PaymentClientType.TOSS, "paymentKey", "orderId", 1000L);

    private WireMockServer wireMockServer;
    private PaymentClient paymentClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();

        // 캐싱 동작과 무관하게 상태 코드 처리만 검증하기 위해 프록시 없이 생성한다.
        IdempotencyInterceptor idempotencyInterceptor =
                new IdempotencyInterceptor(new CacheableIdempotencyService());

        RestClient restClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor(idempotencyInterceptor)
                .defaultStatusHandler(new TossPaymentErrorHandler())
                .build();

        paymentClient = new TossPaymentClient(restClient);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @DisplayName("PG가 사용자 귀책 오류를 반환하면 해당 예외로 전달된다")
    @Test
    void propagateUserInputError() {
        stubConfirm(400, """
                {"code":"REJECT_ACCOUNT_PAYMENT","message":"잔액부족으로 결제에 실패했습니다."}
                """);

        assertThatThrownBy(() -> paymentClient.confirm(REQUEST))
                .isInstanceOf(PaymentBusinessException.class)
                .hasMessage("잔액부족으로 결제에 실패했습니다.");
    }

    @DisplayName("PG가 서버 오류를 반환하면 인프라 계열 예외로 전달된다")
    @Test
    void propagateProviderError() {
        stubConfirm(500, """
                {"code":"FAILED_INTERNAL_SYSTEM_PROCESSING","message":"내부 시스템 처리 작업이 실패했습니다."}
                """);

        assertThatThrownBy(() -> paymentClient.confirm(REQUEST))
                .isInstanceOf(PaymentProviderException.class);
    }

    @DisplayName("PG가 승인에 성공하면 응답 본문이 그대로 전달된다")
    @Test
    void returnSuccessResponse() {
        stubConfirm(200, """
                {"paymentKey":"paymentKey","orderId":"orderId","totalAmount":1000}
                """);

        ConfirmPaymentResponseFromClient response = paymentClient.confirm(REQUEST);

        assertThat(response).isEqualTo(
                new ConfirmPaymentResponseFromClient("paymentKey", "orderId", 1000L));
    }

    private void stubConfirm(final int status, final String body) {
        wireMockServer.stubFor(post(urlEqualTo("/confirm"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(body)));
    }
}
