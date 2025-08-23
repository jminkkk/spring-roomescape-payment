package roomescape.payment.client.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import roomescape.payment.client.dto.response.ConfirmPaymentResponseFromClient;

@SpringBootTest
class CacheableIdempotencyServiceTest {

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
        String idempotencyKey = "test-idempotency-key";
        AtomicInteger counter = new AtomicInteger(0);

        Supplier<ConfirmPaymentResponseFromClient> pgCall = () -> {
            int count = counter.incrementAndGet();
            return new ConfirmPaymentResponseFromClient("payment key " + count, "order id " + count, 100L);
        };

        // when
        ConfirmPaymentResponseFromClient firstCall = cacheableIdempotencyService.getOrCallPg(idempotencyKey, pgCall);

        // 캐시 확인
        ConfirmPaymentResponseFromClient secondCall = cacheableIdempotencyService.getOrCallPg(idempotencyKey, pgCall);

        // then
        assertThat(firstCall).isNotNull();
        assertThat(secondCall).isNotNull();
        assertThat(firstCall).isEqualTo(secondCall);
        assertThat(counter.get()).isEqualTo(1);

        // 캐시에 값이 들어갔는지 확인
        Object cached = cacheManager.getCache("idempotentPayments")
                .get(idempotencyKey, ConfirmPaymentResponseFromClient.class);
        assertThat(cached).isEqualTo(firstCall);
    }
}
