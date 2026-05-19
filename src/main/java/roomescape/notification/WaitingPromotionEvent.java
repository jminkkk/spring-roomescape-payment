package roomescape.notification;

import java.time.LocalDate;
import java.time.LocalTime;

import roomescape.common.OutboxEventType;
import roomescape.common.outbox.OutboxEvent;
import roomescape.reservation.model.Reservation;

public record WaitingPromotionEvent(
        Long reservationId,
        String toEmail,
        String memberName,
        LocalDate date,
        LocalTime startAt,
        String themeName
) implements OutboxEvent {

    @Override
    public OutboxEventType eventType() {
        return OutboxEventType.EMAIL_SEND_FAILED;
    }

    public static WaitingPromotionEvent from(Reservation reservation) {
        return new WaitingPromotionEvent(
                reservation.getId(),
                reservation.getMember().getEmail().getEmail(),
                reservation.getMember().getName(),
                reservation.getDate(),
                reservation.getReservationTime().getStartAt(),
                reservation.getTheme().getName()
        );
    }
}
