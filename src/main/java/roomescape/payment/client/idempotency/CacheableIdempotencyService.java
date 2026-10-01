package roomescape.payment.client.idempotency;

import java.util.function.Supplier;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class CacheableIdempotencyService {

    @Cacheable(value = "idempotentPayments", key = "#idempotencyKey")
    public CachedPaymentResponse getOrCallPg(String idempotencyKey, Supplier<CachedPaymentResponse> pgCall) {
        return pgCall.get();
    }
}
