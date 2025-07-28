package roomescape.payment.client.toss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static roomescape.payment.client.toss.TossErrorCode.EXCEED_MAX_ONE_DAY_AMOUNT;
import static roomescape.payment.client.toss.TossErrorCode.INVALID_UNREGISTERED_SUBMALL;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TossErrorCodeTest {

    @Test
    @DisplayName("유저에게 노출해선 안되는 에러 코드: 참")
    void isNotForUser() {
        assertTrue(INVALID_UNREGISTERED_SUBMALL.isNotForUser());
    }

    @Test
    @DisplayName("유저에게 노출해도 되는 에러 코드: 참")
    void isForUser() {
        assertFalse(EXCEED_MAX_ONE_DAY_AMOUNT.isNotForUser());
    }
}
