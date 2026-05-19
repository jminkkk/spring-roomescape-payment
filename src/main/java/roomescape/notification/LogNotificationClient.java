package roomescape.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LogNotificationClient implements NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationClient.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("[알림 발송] to={}, subject={}, body={}", to, subject, body);
    }
}
