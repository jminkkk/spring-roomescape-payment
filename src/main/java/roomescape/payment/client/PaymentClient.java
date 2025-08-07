package roomescape.payment.client;

import java.util.logging.Logger;

import roomescape.common.exception.ClientException;
import roomescape.payment.client.dto.ConfirmPaymentRequest;
import roomescape.payment.model.PaymentInfoFromClient;

public abstract class PaymentClient {

    private final Logger logger = Logger.getLogger("Logger");
    protected static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    public abstract PaymentClientType getPaymentProvider();

    public abstract PaymentInfoFromClient confirm(ConfirmPaymentRequest confirmPaymentRequest, String idempotencyKey);

    public PaymentInfoFromClient fallbackConfirm(Exception ex) {
        PaymentClientType paymentClientType = getPaymentProvider();

        logger.warning("Payment provider " + paymentClientType + " unavailable, error: " + ex.getMessage());

        throw new ClientException(paymentClientType + " 결제 서비스에 일시적 장애가 발생했습니다. 다른 결제 수단을 선택해주세요.");
    }
}
