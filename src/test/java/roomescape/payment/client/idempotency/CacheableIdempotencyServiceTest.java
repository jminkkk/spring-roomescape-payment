package roomescape.payment.client.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;

import roomescape.config.RedisTestContainersConfig;

@SpringBootTest
@RedisTestContainersConfig
class CacheableIdempotencyServiceTest {

    private static final String IDEMPOTENCY_KEY = "test-idempotency-key";

    @Autowired private CacheableIdempotencyService cacheableIdempotencyService;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        var cache = cacheManager.getCache("idempotentPayments");
        if (cache != null) {
            cache.clear();
        }
    }

    @DisplayName("같은 idempotencyKey 요청은 pgCall 한번만 실행된다")
    @Test
    void pgCall() {
        // given
        AtomicInteger counter = new AtomicInteger(0);
        Supplier<CachedPaymentResponse> pgCall = () -> {
            counter.incrementAndGet();
            return successResponse();
        };

        // when
        CachedPaymentResponse firstCall = cacheableIdempotencyService.getOrCallPg(IDEMPOTENCY_KEY, pgCall);
        CachedPaymentResponse secondCall = cacheableIdempotencyService.getOrCallPg(IDEMPOTENCY_KEY, pgCall);

        // then
        assertThat(counter.get()).isEqualTo(1);
        assertThat(secondCall.statusCode()).isEqualTo(firstCall.statusCode());
        assertThat(secondCall.body()).isEqualTo(firstCall.body());
    }

    @DisplayName("실패 응답은 캐싱하지 않아 다음 요청에서 다시 PG를 호출한다")
    @Test
    void doesNotCacheFailure() {
        // given
        AtomicInteger counter = new AtomicInteger(0);
        Supplier<CachedPaymentResponse> failingPgCall = () -> {
            counter.incrementAndGet();
            throw new NonCacheablePgResponseException(HttpStatus.BAD_REQUEST, "error".getBytes(StandardCharsets.UTF_8));
        };

        // when
        assertThatThrownBy(() -> cacheableIdempotencyService.getOrCallPg(IDEMPOTENCY_KEY, failingPgCall))
                .isInstanceOf(NonCacheablePgResponseException.class);
        assertThatThrownBy(() -> cacheableIdempotencyService.getOrCallPg(IDEMPOTENCY_KEY, failingPgCall))
                .isInstanceOf(NonCacheablePgResponseException.class);

        // then
        assertThat(counter.get()).isEqualTo(2);
        assertThat(cacheManager.getCache("idempotentPayments").get(IDEMPOTENCY_KEY)).isNull();
    }

    @DisplayName("실패 이후 성공한 요청은 정상적으로 캐싱된다")
    @Test
    void cachesAfterRecovery() {
        // given
        AtomicInteger counter = new AtomicInteger(0);
        Supplier<CachedPaymentResponse> recoveringPgCall = () -> {
            if (counter.incrementAndGet() == 1) {
                throw new NonCacheablePgResponseException(HttpStatus.SERVICE_UNAVAILABLE, new byte[0]);
            }
            return successResponse();
        };

        // when
        assertThatThrownBy(() -> cacheableIdempotencyService.getOrCallPg(IDEMPOTENCY_KEY, recoveringPgCall))
                .isInstanceOf(NonCacheablePgResponseException.class);
        cacheableIdempotencyService.getOrCallPg(IDEMPOTENCY_KEY, recoveringPgCall);
        cacheableIdempotencyService.getOrCallPg(IDEMPOTENCY_KEY, recoveringPgCall);

        // then
        assertThat(counter.get()).isEqualTo(2);
    }

    private CachedPaymentResponse successResponse() {
        return new CachedPaymentResponse(200,
                """
                {"paymentKey":"paymentKey","orderId":"orderId","totalAmount":1000}
                """.getBytes(StandardCharsets.UTF_8));
    }
}
