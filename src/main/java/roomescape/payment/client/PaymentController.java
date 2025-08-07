package roomescape.payment.client;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import roomescape.payment.service.PaymentService;

@RestController
@RequestMapping("/payment")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(final PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/client")
    public ResponseEntity<Map<PaymentClientType, PaymentClientStatus>> getPaymentProvidersStatus() {
        Map<PaymentClientType, PaymentClientStatus> paymentClientStatuses = paymentService.getPaymentClientStatuses();
        return ResponseEntity.ok(paymentClientStatuses);
    }
}
