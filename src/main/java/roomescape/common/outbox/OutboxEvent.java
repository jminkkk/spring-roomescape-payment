package roomescape.common.outbox;

import roomescape.common.OutboxEventType;

public interface OutboxEvent {
    OutboxEventType eventType();
}
