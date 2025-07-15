package roomescape.payment.client;

import java.util.logging.Logger;

import org.springframework.retry.annotation.CircuitBreaker;

import roomescape.common.exception.ClientException;
import roomescape.payment.client.dto.ConfirmPaymentRequest;
import roomescape.payment.model.PaymentInfoFromClient;

public abstract class PaymentClient {

    private final Logger logger = Logger.getLogger("Logger");
    protected static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    public abstract String getProviderName();

    @CircuitBreaker(recover = "fallbackConfirm")
    public abstract PaymentInfoFromClient confirm(ConfirmPaymentRequest confirmPaymentRequest, String idempotencyKey);

    public PaymentInfoFromClient fallbackConfirm(Exception ex) {
        String displayName = getProviderName();

        logger.warning("Payment provider " + displayName + "unavailable, error: " + ex.getMessage());

        throw new ClientException(displayName + " 결제 서비스에 일시적 장애가 발생했습니다. 다른 결제 수단을 선택해주세요.");
    }
}
