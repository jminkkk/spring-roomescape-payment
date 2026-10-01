package roomescape.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import roomescape.auth.domain.AuthInfo;
import roomescape.member.domain.Role;
import roomescape.fixture.MemberFixture;
import roomescape.member.domain.Member;
import roomescape.member.repository.MemberRepository;
import roomescape.reservation.model.Reservation;
import roomescape.reservation.repository.ReservationRepository;
import roomescape.reservation.service.ReservationService;
import roomescape.reservationtime.model.ReservationTime;
import roomescape.reservationtime.repository.ReservationTimeRepository;
import roomescape.theme.model.Theme;
import roomescape.theme.repository.ThemeRepository;
import roomescape.util.IntegrationTest;
import roomescape.waiting.model.Waiting;
import roomescape.waiting.repository.WaitingRepository;

@IntegrationTest
class WaitingPromotionEventListenerTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ReservationTimeRepository reservationTimeRepository;

    @Autowired
    private ThemeRepository themeRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private WaitingRepository waitingRepository;

    @Autowired
    private SpyNotificationClient spyNotificationClient;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        public SpyNotificationClient spyNotificationClient() {
            return new SpyNotificationClient();
        }
    }

    static class SpyNotificationClient implements NotificationClient {

        private final AtomicReference<String> executedThreadName = new AtomicReference<>();
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void send(String to, String subject, String body) {
            executedThreadName.set(Thread.currentThread().getName());
            latch.countDown();
        }

        public String getExecutedThreadName(long timeoutMs) throws InterruptedException {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            return executedThreadName.get();
        }
    }

    @DisplayName("예약 취소로 승격 시 알림이 notification- 스레드에서 비동기로 실행된다")
    @Test
    void promotionNotificationRunsOnAsyncThread() throws InterruptedException {
        // given
        Member reservationOwner = memberRepository.save(MemberFixture.getOne("owner@test.com"));
        Member waitingMember = memberRepository.save(MemberFixture.getOne("waiter@test.com"));
        ReservationTime time = reservationTimeRepository.save(new ReservationTime(LocalTime.parse("20:00")));
        Theme theme = themeRepository.save(new Theme("테마", "설명", "썸네일"));
        Reservation reservation = reservationRepository.save(new Reservation(reservationOwner, LocalDate.parse("2028-01-01"), time, theme));
        waitingRepository.save(new Waiting(reservation, waitingMember));

        // when
        reservationService.deleteReservation(
                new AuthInfo(reservationOwner.getId(), reservationOwner.getName(), Role.USER),
                reservation.getId());

        // then
        String threadName = spyNotificationClient.getExecutedThreadName(3000);
        assertThat(threadName).startsWith("notification-");
    }
}
