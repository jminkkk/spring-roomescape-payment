package roomescape.payment.client.idempotency;

import java.util.function.Supplier;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import roomescape.payment.client.dto.response.ConfirmPaymentResponseFromClient;

@Service
public class CacheableIdempotencyService {

    @Cacheable(value = "idempotentPayments", key = "#idempotencyKey")
    public ConfirmPaymentResponseFromClient getOrCallPg(String idempotencyKey, Supplier<ConfirmPaymentResponseFromClient> pgCall) {
        return pgCall.get();
    }
}
