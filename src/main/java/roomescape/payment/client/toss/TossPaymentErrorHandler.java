package roomescape.payment.client.toss;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import roomescape.payment.client.exception.PaymentBusinessException;
import roomescape.payment.client.exception.PaymentClientException;
import roomescape.payment.client.exception.PaymentException;
import roomescape.payment.client.exception.PaymentInfrastructureException;
import roomescape.payment.client.exception.PaymentProviderException;
import roomescape.payment.client.exception.PaymentStateException;

public class TossPaymentErrorHandler implements ResponseErrorHandler {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean hasError(final ClientHttpResponse response) throws IOException {
        return response.getStatusCode().is4xxClientError() || response.getStatusCode().is5xxServerError();
    }

    @Override
    public void handleError(final URI url, final HttpMethod method, final ClientHttpResponse response)
            throws IOException {
        TossClientErrorResponse tossClientErrorResponse = objectMapper.readValue(response.getBody(),
                TossClientErrorResponse.class);

        TossErrorCode errorCode = TossErrorCode.fromCode(tossClientErrorResponse.code());

        if (errorCode.isNotForUser()) {
            throw convertToPaymentException(errorCode, "결제 오류입니다. 같은 문제가 반복된다면 문의해주세요.");
        }

        throw convertToPaymentException(errorCode, errorCode.getMessage());
    }

    private PaymentException convertToPaymentException(TossErrorCode errorCode, String message) {
        return switch (errorCode.getType()) {
            case INFRASTRUCTURE -> new PaymentInfrastructureException(message);
            case PROVIDER_ERROR -> new PaymentProviderException(message);
            case POLICY_VIOLATION, USER_INPUT_ERROR -> new PaymentBusinessException(message);
            case CLIENT_ERROR -> new PaymentClientException(message);
            case STATE_TEMPORARY -> new PaymentStateException(message, true);
            case STATE_FINAL -> new PaymentStateException(message, false);
        };
    }
}
