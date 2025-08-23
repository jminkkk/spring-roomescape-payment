package roomescape.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import roomescape.payment.client.PaymentClient;
import roomescape.payment.client.PaymentClientStatus;
import roomescape.payment.client.PaymentClientType;
import roomescape.payment.client.kakao.KaKaoPaymentClient;
import roomescape.payment.client.toss.TossPaymentClient;
import roomescape.payment.outbox.FailedPaymentRegister;
import roomescape.payment.repository.PaymentRepository;
import roomescape.util.DatabaseIsolation;

@SpringBootTest
@ActiveProfiles("test")
@DatabaseIsolation
class PaymentServiceTest {

    @Mock private CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock private CircuitBreaker tossCircuitBreaker;
    @Mock private FailedPaymentRegister failedPaymentRegister;
    @Autowired private PaymentRepository paymentRepository;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        PaymentClient tossPaymentClient = mock(TossPaymentClient.class);
        when(tossPaymentClient.getPaymentProvider()).thenReturn(PaymentClientType.TOSS);

        PaymentClient kakaoPaymentClient = mock(KaKaoPaymentClient.class);
        when(kakaoPaymentClient.getPaymentProvider()).thenReturn(PaymentClientType.KAKAO);

        for (PaymentClientType type : PaymentClientType.values()) {
            CircuitBreaker mockBreaker = mock(CircuitBreaker.class);
            when(circuitBreakerRegistry.circuitBreaker(type.name().toLowerCase() + "-payment")).thenReturn(mockBreaker);
            when(mockBreaker.getState()).thenReturn(State.OPEN);
        }

        List<PaymentClient> paymentClients = List.of(tossPaymentClient, kakaoPaymentClient);
        paymentService = new PaymentService(paymentRepository, failedPaymentRegister, paymentClients, circuitBreakerRegistry);
    }

    @Test
    @DisplayName("서킷 브레이커가 CLOSED 상태일 때 해당 PG사의 사용 가능한 상태를 반환한다")
    void getPaymentClientStatuses_WhenCircuitBreakerClosed_ShouldReturnAvailable() {
        // given
        when(circuitBreakerRegistry.circuitBreaker("toss-payment"))
                .thenReturn(tossCircuitBreaker);

        when(tossCircuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);

        // when
        Map<PaymentClientType, PaymentClientStatus> result = paymentService.getPaymentClientStatuses();

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(PaymentClientType.TOSS).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("서킷 브레이커가 OPEN 상태일 때 해당 PG사의 사용 불가능한 상태를 반환한다")
    void getPaymentClientStatuses_WhenCircuitBreakerOpen_ShouldReturnUnavailable() {
        // given
        when(circuitBreakerRegistry.circuitBreaker("toss-payment"))
                .thenReturn(tossCircuitBreaker);
        when(tossCircuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);

        // when
        Map<PaymentClientType, PaymentClientStatus> result = paymentService.getPaymentClientStatuses();

        // then
        assertThat(result.get(PaymentClientType.TOSS).isAvailable()).isFalse();
    }

    @Test
    @DisplayName("서킷 브레이커가 HALF-OPEN 상태일 때 해당 PG사의 사용 불가능한 상태를 반환한다")
    void getPaymentClientStatuses_WhenCircuitBreakerHalfOpen_ShouldReturnUnavailable() {
        // given
        when(circuitBreakerRegistry.circuitBreaker("toss-payment")).thenReturn(tossCircuitBreaker);
        when(tossCircuitBreaker.getState()).thenReturn(State.HALF_OPEN);

        // when
        Map<PaymentClientType, PaymentClientStatus> result = paymentService.getPaymentClientStatuses();

        // then
        assertThat(result.get(PaymentClientType.TOSS).isAvailable()).isFalse();
    }
}
