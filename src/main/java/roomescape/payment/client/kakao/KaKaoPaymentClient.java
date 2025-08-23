package roomescape.payment.client.kakao;

import org.springframework.stereotype.Component;

import roomescape.payment.client.PaymentClient;
import roomescape.payment.client.PaymentClientType;
import roomescape.payment.client.dto.request.CancelPaymentRequest;
import roomescape.payment.client.dto.request.ConfirmPaymentRequest;
import roomescape.payment.client.dto.response.CancelPaymentResponseFromClient;
import roomescape.payment.client.dto.response.ConfirmPaymentResponseFromClient;

@Component
public class KaKaoPaymentClient extends PaymentClient {

    @Override
    public PaymentClientType getPaymentProvider() {
        return  PaymentClientType.KAKAO;
    }

    @Override
    public ConfirmPaymentResponseFromClient confirm(ConfirmPaymentRequest confirmPaymentRequest) {
        throw new UnsupportedOperationException("KakaoPaymentClient is not implemented yet.");
    }

    @Override
    public CancelPaymentResponseFromClient cancel(CancelPaymentRequest cancelPaymentRequest) {
        throw new UnsupportedOperationException("KakaoPaymentClient is not implemented yet.");
    }
}
