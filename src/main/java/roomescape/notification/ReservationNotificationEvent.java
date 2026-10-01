package roomescape.notification;

import java.time.LocalDate;
import java.time.LocalTime;

import roomescape.common.OutboxEventType;
import roomescape.common.outbox.OutboxEvent;
import roomescape.payment.model.Payment;
import roomescape.reservation.model.Reservation;

public record ReservationNotificationEvent(
        Long reservationId,
        String toEmail,
        String memberName,
        LocalDate date,
        LocalTime startAt,
        String themeName,
        Long amount
) implements OutboxEvent {

    @Override
    public OutboxEventType eventType() {
        return OutboxEventType.EMAIL_SEND_FAILED;
    }

    public static ReservationNotificationEvent toEvent(Reservation reservation, Payment payment) {
        return new ReservationNotificationEvent(
                reservation.getId(),
                reservation.getMember().getEmail().getEmail(),
                reservation.getMember().getName(),
                reservation.getDate(),
                reservation.getReservationTime().getStartAt(),
                reservation.getTheme().getName(),
                payment.getAmount()
        );
    }
}
