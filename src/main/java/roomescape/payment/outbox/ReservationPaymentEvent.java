package roomescape.payment.outbox;

import roomescape.payment.model.Payment;
import roomescape.reservation.model.Reservation;

public record ReservationPaymentEvent(Reservation reservation, Payment payment) {}
