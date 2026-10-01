package roomescape.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import roomescape.common.outbox.FailedEventRegister;

@Component
public class WaitingPromotionEventListener {

    private static final Logger log = LoggerFactory.getLogger(WaitingPromotionEventListener.class);

    private final NotificationService notificationService;
    private final FailedEventRegister failedEventRegister;

    public WaitingPromotionEventListener(NotificationService notificationService,
                                          FailedEventRegister failedEventRegister) {
        this.notificationService = notificationService;
        this.failedEventRegister = failedEventRegister;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(WaitingPromotionEvent event) {
        log.info("Received event {}", event);
        try {
            notificationService.sendPromotionNotification(event);
        } catch (Exception e) {
            log.warn("승격 알림 발송 실패, Outbox에 저장합니다. reservationId={}", event.reservationId(), e);
            failedEventRegister.register(event);
        }
    }
}
