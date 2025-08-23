package roomescape.common.outbox;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import roomescape.common.model.BaseEntity;

@Entity
@Table(name = "outbox_event")
public class Outbox extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String eventType;

    @Lob
    private String payload;

    @Enumerated(EnumType.STRING)
    private OutboxStatus outboxStatus = OutboxStatus.PENDING;

    private LocalDateTime lastTriedAt;

    private int retryCount;

    public Outbox() {
    }

    public Outbox(String eventType, String payload) {
        this.eventType = eventType;
        this.payload = payload;
        this.retryCount = 0;
    }

    public void markCompleted() {
        this.outboxStatus = OutboxStatus.COMPLETED;
    }

    public void markFailed() {
        this.outboxStatus = OutboxStatus.FAILED;
        this.retryCount++;
        this.lastTriedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return outboxStatus;
    }

    public LocalDateTime getLastTriedAt() {
        return lastTriedAt;
    }

    public int getRetryCount() {
        return retryCount;
    }
}

