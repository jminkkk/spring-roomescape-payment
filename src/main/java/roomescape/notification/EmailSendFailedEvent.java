package roomescape.notification;

public record EmailSendFailedEvent(
        Long reservationId,
        String memberEmail,
        String subject,
        String text
) {
}
