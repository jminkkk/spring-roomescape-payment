package roomescape.reservation.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import roomescape.auth.domain.AuthInfo;
import roomescape.common.exception.ForbiddenException;
import roomescape.common.outbox.FailedEventRegister;
import roomescape.member.domain.Member;
import roomescape.member.repository.MemberRepository;
import roomescape.notification.NotificationService;
import roomescape.notification.WaitingPromotionEvent;
import roomescape.payment.service.PaymentService;
import roomescape.reservation.dto.request.CreateMyReservationRequest;
import roomescape.reservation.dto.request.CreateReservationByAdminRequest;
import roomescape.reservation.dto.request.CreateReservationRequest;
import roomescape.reservation.dto.response.CreateReservationResponse;

import roomescape.reservation.dto.response.FindAdminReservationResponse;
import roomescape.reservation.dto.response.FindAvailableTimesResponse;
import roomescape.reservation.dto.response.FindReservationResponse;
import roomescape.reservation.dto.response.FindReservationWithPaymentResponse;
import roomescape.reservation.model.Reservation;
import roomescape.reservation.repository.ReservationRepository;
import roomescape.reservationtime.model.ReservationTime;
import roomescape.reservationtime.repository.ReservationTimeRepository;
import roomescape.theme.model.Theme;
import roomescape.theme.repository.ThemeRepository;
import roomescape.waiting.model.Waiting;
import roomescape.waiting.repository.WaitingRepository;
import roomescape.waiting.service.WaitingService;

@Service
@Transactional
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final WaitingService waitingService;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final FailedEventRegister failedEventRegister;

    private final ReservationRepository reservationRepository;
    private final ReservationTimeRepository reservationTimeRepository;
    private final ThemeRepository themeRepository;
    private final MemberRepository memberRepository;
    private final WaitingRepository waitingRepository;

    public ReservationService(final WaitingService waitingService, PaymentService paymentService,
                              final NotificationService notificationService,
                              final FailedEventRegister failedEventRegister,
                              final ReservationRepository reservationRepository,
                              final ReservationTimeRepository reservationTimeRepository,
                              final ThemeRepository themeRepository,
                              final MemberRepository memberRepository,
                              final WaitingRepository waitingRepository) {
        this.waitingService = waitingService;
        this.paymentService = paymentService;
        this.notificationService = notificationService;
        this.failedEventRegister = failedEventRegister;
        this.reservationRepository = reservationRepository;
        this.reservationTimeRepository = reservationTimeRepository;
        this.themeRepository = themeRepository;
        this.memberRepository = memberRepository;
        this.waitingRepository = waitingRepository;
    }

    public Reservation createReservation(final AuthInfo authInfo, final CreateMyReservationRequest createMyReservationRequest) {
        CreateReservationRequest createReservationRequest = CreateReservationRequest.of(authInfo.getMemberId(), createMyReservationRequest);
        Reservation reservation = reservationRepository.save(convertToReservation(createReservationRequest));
        paymentService.createInProgressPayment(createMyReservationRequest, reservation);
        return reservation;
    }

    public void cancelReservation(final Long reservationId) {
        Reservation reservation = reservationRepository.getById(reservationId);
        paymentService.deleteInProgressPayment(reservation);
        reservationRepository.deleteById(reservationId);
    }

    public CreateReservationResponse createReservationByAdmin(final CreateReservationByAdminRequest createReservationByAdminRequest) {
        CreateReservationRequest createReservationRequest = CreateReservationRequest.of(createReservationByAdminRequest);
        Reservation reservation = convertToReservation(createReservationRequest);
        return CreateReservationResponse.from(reservationRepository.save(reservation));
    }

    public Reservation convertToReservation(final CreateReservationRequest createReservationRequest) {
        ReservationTime reservationTime = reservationTimeRepository.getById(createReservationRequest.timeId());
        Theme theme = themeRepository.getById(createReservationRequest.themeId());
        Member member = memberRepository.getById(createReservationRequest.memberId());

        checkAlreadyExistReservation(createReservationRequest, theme.getName(), reservationTime.getStartAt());
        return createReservationRequest.toReservation(member, reservationTime, theme);
    }

    private void checkAlreadyExistReservation(final CreateReservationRequest createReservationRequest,
                                              final String themeName, final LocalTime time) {
        if (reservationRepository.existsByDateAndReservationTimeIdAndThemeId(
                createReservationRequest.date(),
                createReservationRequest.timeId(),
                createReservationRequest.themeId())) {
            throw new IllegalArgumentException("이미 " + createReservationRequest.date() + "의 " + themeName + " 테마에는 " + time
                    + " 시의 예약이 존재하여 예약을 생성할 수 없습니다.");
        }
    }

    public List<FindAdminReservationResponse> getReservations() {
        return reservationRepository.findAll().stream()
                .map(FindAdminReservationResponse::from)
                .toList();
    }

    public FindReservationResponse getReservation(final Long id) {
        Reservation reservation = reservationRepository.getById(id);
        return FindReservationResponse.from(reservation);
    }

    public List<FindReservationWithPaymentResponse> getReservations(final AuthInfo authInfo) {
        return reservationRepository.findReservationWithPaymentsByMemberId(authInfo.getMemberId()).stream()
                .map(FindReservationWithPaymentResponse::from)
                .toList();
    }

    public List<FindAvailableTimesResponse> getAvailableTimes(final LocalDate date, final Long themeId) {
        List<ReservationTime> reservationTimes = reservationTimeRepository.findAll();
        List<Reservation> reservations = reservationRepository.findAllByDateAndThemeId(date, themeId);
        return reservationTimes.stream()
                .map(reservationTime -> generateFindAvailableTimesResponse(reservations, reservationTime))
                .toList();
    }

    private static FindAvailableTimesResponse generateFindAvailableTimesResponse(final List<Reservation> reservations,
                                                                                 final ReservationTime reservationTime) {
        return FindAvailableTimesResponse.from(
                reservationTime,
                reservations.stream()
                        .anyMatch(reservation -> reservation.isSameTime(reservationTime)));
    }

    public List<FindReservationResponse> searchBy(final Long themeId, final Long memberId,
                                                  final LocalDate dateFrom, final LocalDate dateTo) {
        return reservationRepository.findAllByThemeIdAndMemberIdAndDateBetween(themeId, memberId, dateFrom, dateTo).stream()
                .map(FindReservationResponse::from)
                .toList();
    }

    public void deleteReservation(final AuthInfo authInfo, final Long id) {
        Reservation reservation = reservationRepository.getById(id);
        checkCancelAuthorization(reservation, authInfo.getMemberId());

        if (waitingRepository.existsByReservation(reservation)) {
            updateReservationByMember(reservation);
            return;
        }
        reservationRepository.deleteById(id);
    }

    private void checkCancelAuthorization(final Reservation reservation, final Long memberId) {
        Member member = memberRepository.getById(memberId);
        if (member.isNotAdmin() && !reservation.isOwnedBy(member)) {
            throw new ForbiddenException(
                    "식별자 " + reservation.getId() + "인 예약에 대해 회원 식별자 " + memberId + "의 권한이 존재하지 않아, 삭제가 불가능합니다.");
        }
    }

    private void updateReservationByMember(final Reservation reservation) {
        Waiting waiting = waitingRepository.getFirstByReservation(reservation);
        reservation.updateMember(waiting.getMember());
        waitingService.deleteWaitingForReservationUpgrade(waiting.getId());
        sendPromotionNotification(reservation);
    }

    private void sendPromotionNotification(final Reservation reservation) {
        WaitingPromotionEvent event = WaitingPromotionEvent.from(reservation);
        try {
            notificationService.sendPromotionNotification(event);
        } catch (Exception e) {
            log.warn("승격 알림 발송 실패, Outbox에 저장합니다. reservationId={}", reservation.getId(), e);
            failedEventRegister.register(event);
        }
    }
}
