package roomescape.notification;

public interface NotificationClient {

    void send(String to, String subject, String body);
}
