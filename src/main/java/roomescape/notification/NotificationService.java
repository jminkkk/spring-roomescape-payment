package roomescape.notification;

import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NotificationClient notificationClient;

    public NotificationService(NotificationClient notificationClient) {
        this.notificationClient = notificationClient;
    }

    public void sendPromotionNotification(WaitingPromotionEvent event) {
        String subject = "[방탈출] 대기가 예약으로 승격되었습니다";
        String body = String.format("""
                %s 님, 대기가 예약으로 승격되었습니다.

                - 날짜: %s
                - 시간: %s
                - 테마: %s

                결제를 완료해주세요.
                """,
                event.memberName(),
                event.date(),
                event.startAt(),
                event.themeName()
        );
        notificationClient.send(event.toEmail(), subject, body);
    }
}
