package roomescape.reservation.service;

import org.springframework.stereotype.Service;

import roomescape.auth.domain.AuthInfo;
import roomescape.payment.client.dto.request.ConfirmPaymentRequest;
import roomescape.payment.model.Payment;
import roomescape.payment.service.PaymentService;
import roomescape.reservation.dto.request.CreateMyReservationRequest;
import roomescape.reservation.dto.response.CreateReservationResponse;
import roomescape.reservation.model.Reservation;

@Service
public class ReservationApplicationService {

    private final ReservationService reservationService;
    private final PaymentService paymentService;

    public ReservationApplicationService(ReservationService reservationService, PaymentService paymentService) {
        this.reservationService = reservationService;
        this.paymentService = paymentService;
    }

    public CreateReservationResponse createReservation(final AuthInfo authInfo, final CreateMyReservationRequest request) {
        Reservation reservation = reservationService.createReservation(authInfo, request);

        try {
            paymentService.callPG(ConfirmPaymentRequest.from(request));
        } catch (Exception e) {
            reservationService.cancelReservation(reservation.getId());
            throw e;
        }

        Payment payment = paymentService.completePayment(reservation);
        return CreateReservationResponse.from(reservation, payment);
    }
}
