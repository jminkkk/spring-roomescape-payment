package roomescape.payment.client;

import java.util.logging.Logger;

import roomescape.common.exception.ClientException;
import roomescape.payment.client.dto.request.ConfirmPaymentRequest;
import roomescape.payment.client.dto.response.CancelPaymentResponseFromClient;
import roomescape.payment.client.dto.request.CancelPaymentRequest;
import roomescape.payment.client.dto.response.ConfirmPaymentResponseFromClient;

public abstract class PaymentClient {

    private final Logger logger = Logger.getLogger("Logger");
    protected static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    public abstract PaymentClientType getPaymentProvider();

    public abstract ConfirmPaymentResponseFromClient confirm(ConfirmPaymentRequest confirmPaymentRequest);

    public ConfirmPaymentResponseFromClient fallbackConfirm(ConfirmPaymentRequest confirmPaymentRequest, Throwable ex) {
        PaymentClientType paymentClientType = getPaymentProvider();

        logger.warning("Payment provider " + paymentClientType + " unavailable, error: " + ex.getMessage());

        throw new ClientException(paymentClientType + " 결제 서비스에 일시적 장애가 발생했습니다. 다른 결제 수단을 선택해주세요.");
    }

    public abstract CancelPaymentResponseFromClient cancel(CancelPaymentRequest cancelPaymentRequest);
}
