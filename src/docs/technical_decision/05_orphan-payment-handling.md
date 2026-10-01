> 이전 글에서는 연동 서버의 장애가 우리 서버까지 미치지 않도록 서킷 브레이커 패턴을 적용하고 비정상 PG사를 비노출하여 사용자 경험을 개선해보았다.
이번 글에서는 연동 서버가 아닌 우리 서버에서 발생할 수 있는 고아 객체 처리에 대해 다루어보겠다.
>

서비스 운영 과정에서 종종 마주할 수 있는 치명적인 문제가 있다.

바로 **외부 PG사와의 결제 연동은 성공했는데, 우리 DB에 저장하는 단계에서 실패하는 경우**이다.

이 상황은 단순한 예약 검증 실패보다 훨씬 치명적인데, 결제는 이미 성공했는데 우리 시스템에는 반영되지 않는 “고아(Orphan) 결제”가 생기기 때문이다.

이 글에서는 고아 결제가 왜 발생하는지 알아보고, 3단계에 걸친 복구 전략을 통해 데이터 정합성을 확보해 나가는 과정을 공유한다.

### 기존 결제 프로세스

![](https://velog.velcdn.com/images/jminkkk/post/85b3c29e-e966-4c1f-9867-23a547bb93d3/image.png)

## **어떤 경우에 발생할까?**

운영 환경에서는 다양한 예상치 못한 예외가 발생할 수 있다.

- **비즈니스 로직 오류:** 비즈니스 규칙 검증 실패
- **일시적 장애**: 네트워크 끊김, DB 락, 순간적인 연결 문제
- **영구적 장애**: DB 스키마 문제, 제약 조건 위반

이때 중요한 것은 외부 시스템(PG) 호출 후 내부 로직이 실패하면 **롤백이 불가능**하다는 것이다.

# **3단계 복구 전략으로** 일관성 보장하기

이 문제를 해결하기 위해 순차적으로 개선 전략을 적용했다.

## **1단계: 제어 가능한 로직을 먼저 처리 ( 로직 순서 변경 )**

가장 먼저 시도한 방법은 **프로세스 순서를 변경**하는 것이었다.

- **AS-IS:** `결제 요청` → `예약(재고 확인 등) 및 결제 정보 저장`
- **TO-BE:** `예약(재고 확인 등) 정보 저장` → `결제 요청` → `결제 정보 업데이트`

결제보다 실패할 확률이 높은 비즈니스 로직(예: 중복 예약, 과거 날짜 선택)을 먼저 검증하고 처리하는 방식이다. 이렇게 하면 예약 단계에서 실패할 경우 아예 결제 요청 자체를 보내지 않으므로, 고아 결제가 발생할 가능성을 크게 줄일 수 있다.

![](https://velog.velcdn.com/images/jminkkk/post/d25ec4d8-bdb5-485d-acef-e36fd5edf13e/image.png)

하지만 여전히 4번(결제 성공) 이후 5번(DB 저장) 단계에서 실패하면 고아 결제 문제가 발생하기 때문에 이 방법도 완벽하진 않다.

## 2단계: '재시도'와 '보상 트랜잭션' 도입

DB 락(Lock) 경합, 트랜잭션 타임아웃 등 일시적으로 후속 작업인 DB 저장에 실패한다면, 재시도 로직으로 복구될 가능성이 높다고 판단했다.

```java
@Service
public class PaymentService {
		// 생략
		@Transactional
		public void createPayment(final ConfirmPaymentRequest confirmPaymentRequest, final Reservation reservation) {
        PaymentClient paymentClient = paymentClients.get(confirmPaymentRequest.providerName());
        PaymentInfoFromClient paymentInfoFromClient = paymentClient.confirm(confirmPaymentRequest, generateIdempotencyKey(reservation.getId()));
        Payment payment = paymentInfoFromClient.toPayment(reservation);
        savePayment(payment);
    }

	// 3회 재시도 처리
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public void savePayment(final Payment payment) {
        paymentRepository.save(payment);
    }
}

```

### 영구적 장애는 보상 트랜잭션으로 즉시 취소?

만약 재시도를 해도 DB 스키마 오류나 제약 조건 위반처럼 영구적인 문제로 저장이 계속 실패한다면, 보상 트랜잭션(Compensation Transaction)을 수행하는 것이다.

보상 트랜잭션이란, 이미 완료된 작업을 되돌리는 별도의 작업을 수행하는 것을 의미합니다. 즉, DB 저장이 최종 실패하면 **즉시 PG사에 결제 취소 API를 호출**해 없던 일로 만드는 것이다.

> PG 결제 수단이 가상계좌, 휴대폰 소액결제 등 같이 사용자가 추가적으로 개입해야 한다면 사용이 불가능하지만 카드 같은 서비스라면 우리 시스템에서 임의로 취소 API를 전송할 수 있다.
>

카드결제, 간편결제 등 즉시 취소가 가능한 수단에 대해서는 **보상 트랜잭션**을 수행하도록 하였다.

```java
@Service
public class PaymentService {

		@Transactional
    public void createPayment(final ConfirmPaymentRequest confirmPaymentRequest, final Reservation reservation) {
        PaymentClient paymentClient = paymentClients.get(confirmPaymentRequest.providerName());
        ConfirmPaymentResponseFromClient confirmPaymentResponseFromClient = paymentClient.confirm(
                confirmPaymentRequest);
        Payment payment = confirmPaymentResponseFromClient.toPayment(reservation);
        savePayment(paymentClient, payment);
    }

    public Payment savePayment(final PaymentClient paymentClient, final Payment payment) {
        try {
            return paymentRepository.save(payment);
        } catch (Exception e) {
            log.error("결제 저장에 실패했습니다. {}", payment);
            CancelPaymentRequest cancelPaymentRequest = new CancelPaymentRequest(paymentClient.getPaymentProvider(), payment.getPaymentKey(), SAVE_PAYMENT_FAILURE_MESSAGE, payment.getAmount());
            paymentClient.cancel(cancelPaymentRequest);
        }
        throw new IllegalStateException("결제 저장에 실패했습니다. 결제를 취소했습니다.");
    }

```

보상 트랜잭션은 데이터 정합성을 즉시 맞출 수 있다는 장점이 있다.

하지만, 문제는 사용자는 예약과 결제가 성공해야 할 상태에서 결제 DB 저장만 실패로 인해 전체 요청이 실패된 상황이다. 이 경우 보상 트랜잭션(결제 취소)을 수행하면, 고객은 예약 성공을 기대했는데 예약 취소로 인해 혼란을 겪을 수 있다고 판단했다.

특히 방탈출 예약처럼 **재고가 한정적인 서비스**에서는 문제가 더 커집니다. 어렵게 성공한 예약을 시스템이 임의로 취소해버리면 사용자가 서비스의 안정성에 의문을 느낄 수 있다.

따라서 '즉시 취소'보다는 '일단 성공으로 간주하고 나중에 보정'하는 방식이 더 나은 사용자 경험을 제공한다고 판단했다.

## **3단계: '궁극적 일관성'으로 고아 결제 잡기**

우리 서비스의 DB 저장에서 실패했더라도, PG에는 결제 성공 기록이 남아있다. 이를 활용해 **궁극적 일관성**을 확보하도록 하는 방안을 고민하였다.

1. PG사의 API 을 이용하여 주기적으로 전체 거래를 대사 처리
2. PG사의 Webhook 을 이용하여 성공 이벤트 수신 시 메시지 큐로 처리 이벤트 발행
3. 기존 트랜잭션과 분리 후 엔티티의 상태를 관리하여 PENDING 건에 한하여 폴링하여 재시도 처리

### 1. PG사의 API 을 이용하여 전체 거래 내역을 대사 처리

PG 사의 거래 조회 API를 통해 특정 기간에 일어난 모든 결제 승인 및 취소의 요약을 조회하여 거래 기록을 비교하는 방법입니다.

*'거래 대사'라고 부르기도 합니다. '대사'란 기록을 비교・대조해서 데이터를 검증하고 일치시키는 과정을 뜻합니다.*

![](https://velog.velcdn.com/images/jminkkk/post/2526b206-8c07-4c8c-b429-d188a2656347/image.png)

DB 트랜잭션이 롤백되면, 결제 기록이 우리 DB에 저장되지 않는다. 하지만 PG사에는 결제 기록이 남아있으므로, **배치 작업**을 통해 PG사의 결제 기록과 우리 DB의 결제 기록을 주기적으로 대조하는 방법이다.

대조 과정에서 PG사에는 있지만 우리 DB에는 없는 고아 결제를 찾아낼 수 있다.

가장 확실하지만, 실시간 처리가 어렵고 거래량이 많을 경우 시스템 부하가 크다.

### 2. PG사의 Webhook 과 메시지 큐(MQ) 활용

Webhook은 PG사 측에서 우리 서버로 알림을 보내는 방식이다.

토스 페이먼츠는 결제 상태에 대한 웹훅을 등록할 수 있는데 우리 서버의 api를 등록해 놓는다면 결제의 상태가 변경되었을 때마다 요청을 받을 수 있다. PG 사에 제공할 API를 하나 생성한 후 등록해놓으면 된다.

![](https://velog.velcdn.com/images/jminkkk/post/1252b523-d318-4887-bdf9-4aeee34f51f1/image.png)

우리 서버가 저장 실패하더라도 PG에서 다시 상태를 알려주기 때문에 누락되는 일을 막을 수 있습니다.

```java
@RestController
@RequestMapping("/payment")
public class PaymentController {

    @PostMapping("/webhook/toss")
    public ResponseEntity<String> handleWebhook(@RequestBody ClientWebhookRequest clientWebhookRequest) {
        paymentService.updateFromWebhook(clientWebhookRequest);
        return ResponseEntity.ok().build();
    }
}

```

전체 흐름은 이 웹훅을 통해 결제 성공 이벤트를 메시지 큐에 발행하고 이후 비동기적으로 처리한다.

이 방식은 결제 요청-응답 프로세스와 웹훅 처리 로직을 분리하여 **시스템 간의 결합도를 낮추고**, 여러 Consumer가 이벤트를 병렬로 처리할 수 있어 **전체 처리량 향상**에도 유리하다.

```java
@Service
public class PaymentService {

    private final DomainEventPublisher eventPublisher; // 이 서비스가 존재한다고 가정

		// 생략
    public void updateFromWebhook(ClientWebhookRequest clientWebhookRequest, Reservation reservation) {
        messageQueuePublisher.publishPaymentWebhookEvent(clientWebhookRequest);
    }
}

@Component
public class PaymentEventConsumer {

    @RabbitListener(queues = "payment.queue", concurrency = "5-10")
    public void handlePaymentEvent(PaymentEvent event) {
        processPayment(event);
        // 생략
    }
}

```

하지만 다음의 문제를 고려해야 한다.

1. PG사가 Webhook을 제공하는 것에 대해 의존하는 방식
  1. Webhook을 제공하지 않는 PG에 대해 대응하기 위해서는 별도의 처리가 필요하며, 따라서 메인 처리 방식으로는 적합하지 않다고 판단
  2. 또한 Webhook이 지연되거나 누락되는 경우에 대한 처리가 필요
2. 메시지 큐의 비용 문제
  1. 메시지 브로커 자체에 장애가 발생하면 전체 이벤트 처리에 영향을 줄 수 있으며, 별도의 MQ 인프라를 구성하고 관리해야 하기 때문에 운영 비용과 모니터링 부담이 추가됨
  2. 메시지 순서 보장이나 중복 처리, 실패 재시도 등의 로직을 신경 써야 하기 때문에 설계 복잡도가 다소 증가

가장 큰 문제는 현재 요구사항에서는 결제 성공 이벤트로 파생될 이벤트가 DB 저장 말고는 후행 이벤트가 없었기 때문에 MQ의 도입은 오버엔지니어링이라고 판단하였다.

### 3. 트랜잭션 경계 재설정 및 로그 정보를 통한 재시도 처리 **(최종 선택)**

기존 방식에서는 **결제 성공과 예약 완료를 하나의 트랜잭션에서 원자적으로 처리하였다** . 실제 비즈니스 로직상 결제가 실패하면 예약도 함께 실패해야 하기 때문이다.

**하지만 궁극적인 문제는 DB 장애나 시스템 오류로 인해 같은 트랜잭션이어서 두 정보가 모두 사라지는 상황**이다. 특히 PG사에서는 결제가 성공했는데 우리 시스템에서만 저장이 실패하는 경우, 이 정보를 추적할 방법이 없어진다.

따라서 마지막으로 고려한 방식은 예약과 결제의 원자성은 그대로 유지하면서, 실패 상황만 별도 트랜잭션으로 기록하여 나중에 재시도할 수 있도록 하는 것이다.

실제 저장 중 에러를 임의로 발생시킨 후 트랜잭션이 확인해보면 다음과 같이 동일한 ReservationService.createMyReservation 트랜잭션에 의해 수행된 것을 알 수 있다.

```java
2025-08-15T13:31:15.465+09:00 ERROR 51447 --- [nio-8080-exec-6] r.r.service.ReservationService           : [ ReservationService] Reservation 59
2025-08-15T13:31:15.465+09:00 ERROR 51447 --- [nio-8080-exec-6] r.r.service.ReservationService           : [ ReservationService] Payment roomescape.reservation.service.ReservationService.createMyReservation
2025-08-15T13:31:16.440+09:00 ERROR 51447 --- [nio-8080-exec-6] r.payment.service.PaymentService         : [ PaymentService] Reservation 59
2025-08-15T13:31:16.440+09:00 ERROR 51447 --- [nio-8080-exec-6] r.payment.service.PaymentService         : [ PaymentService] Payment roomescape.reservation.service.ReservationService.createMyReservation
2025-08-15T13:31:16.440+09:00 DEBUG 51447 --- [nio-8080-exec-6] o.s.orm.jpa.JpaTransactionManager        : Found thread-bound EntityManager [SessionImpl(389621048<open>)] for JPA transaction
2025-08-15T13:31:16.440+09:00 DEBUG 51447 --- [nio-8080-exec-6] o.s.orm.jpa.JpaTransactionManager        : Participating in existing transaction
2025-08-15T13:31:16.443+09:00 ERROR 51447 --- [nio-8080-exec-6] r.payment.service.PaymentService         : Error saving payment roomescape.payment.model.Payment@9f4c02f
2025-08-15T13:31:16.443+09:00 ERROR 51447 --- [nio-8080-exec-6] r.payment.service.PaymentService         : [ PaymentService - Rollback] Reservation 59
2025-08-15T13:31:16.443+09:00 ERROR 51447 --- [nio-8080-exec-6] r.payment.service.PaymentService         : [ PaymentService - Rollback] Payment roomescape.reservation.service.ReservationService.createMyReservation
2025-08-15T13:31:18.173+09:00 ERROR 51447 --- [nio-8080-exec-6] r.payment.service.PaymentService         : [ PaymentService - Rollback] Reservation 취소 요청 성공 CancelPaymentResponseFromClient[mId=tgen_docs, paymentKey=tgen_202508151330387LDk6, orderId=WTESTMC40NzY3NDQ1ODkzMDk4, orderName=테스트 방탈출 예약 결제 1건, status=CANCELED, totalAmount=10]
2025-08-15T13:31:18.178+09:00 DEBUG 51447 --- [nio-8080-exec-6] o.s.orm.jpa.JpaTransactionManager        : Initiating transaction rollback
2025-08-15T13:31:18.179+09:00 DEBUG 51447 --- [nio-8080-exec-6] o.s.orm.jpa.JpaTransactionManager        : Rolling back JPA transaction on EntityManager [SessionImpl(389621048<open>)]
2025-08-15T13:31:18.181+09:00 DEBUG 51447 --- [nio-8080-exec-6] o.s.orm.jpa.JpaTransactionManager        : Not closing pre-bound JPA EntityManager after transaction
2025-08-15T13:31:18.182+09:00  WARN 51447 --- [nio-8080-exec-6] Logger                                   : [ERROR] 결제 저장에 실패했습니다. 결제를 취소했습니다.

```

이처럼 결제 서비스가 예약 서비스의 트랜잭션에 참여(Participating in existing transaction)하게 되면, 결제 저장 실패 시 예약 정보까지 모두 롤백되어 버린다.

### **트랜잭션 동기화를 통한 실패 감지 및 처리**

따라서 메인 트랜잭션은 유지하되, `TransactionSynchronizationManager`를 활용하여 현재 트랜잭션의 생명주기에 개입하도록 처리하였다.

이를 통해 기본 트랜잭션이 성공적으로 커밋(Commit)되거나 실패하여 롤백(Rollback)된 직후에 특정 로직을 실행할 수 있도록 콜백을 등록할 수 있다.

```java
@Service
public class PaymentService {

    public Payment createPayment(final ConfirmPaymentRequest confirmPaymentRequest, final Reservation reservation) {
        PaymentClient paymentClient = paymentClients.get(confirmPaymentRequest.providerName());
        ConfirmPaymentResponseFromClient confirmPaymentResponseFromClient = paymentClient.confirm(confirmPaymentRequest);
        Payment payment = confirmPaymentResponseFromClient.toPayment(reservation);

        log.error("[ PaymentService] Reservation {} ", Thread.currentThread().getId());
        log.error("[ PaymentService] Payment {} ", TransactionSynchronizationManager.getCurrentTransactionName());

        try {
            return paymentRepository.save(payment);
        } catch (Exception e) {
            log.error("[ PaymentService] 결제 저장 실패, 결제 키: {}, 에러: {}", payment.getPaymentKey(), e.getMessage());
            failedPaymentRegister.registerFailPayment(payment, reservation.getId());
            throw new IllegalArgumentException("결제 저장에 실패했습니다. 결제 수단을 다시 선택해주세요.");
        }
    }
}

```

전체 흐름은 다름과 같다.

- `paymentRepository.save(payment)`를 통해 결제 정보를 DB에 저장하는 로직 바로 다음에 `registerSynchronization`를 호출하여 콜백을 등록
- 트랜잭션이 **완료된 후** (커밋 또는 롤백)에 실행되는데,`afterCompletion` 메서드의 파라미터인 `status` 값을 확인
  - 만약 이 값이 `STATUS_ROLLED_BACK`이라면, 해당 트랜잭션이 롤백되었음을 의미
  - 바로 이 시점에, 우리는 PG사에는 결제가 성공했지만 우리 DB에는 저장이 실패했다는 사실을 알 수 있다.
- 따라서`failedPaymentRegister.registerFailPayment()`를 호출하여 실패한 결제 정보를 별도로 기록하는 로직을 트리거한다.

### **별도 트랜잭션으로 실패 기록의 영속성 보장**

```java
@Component
public class FailedPaymentRegister {
    // ... 생략 ...
    @Transactional(propagation = REQUIRES_NEW)
    public void registerFailPayment(final Payment payment, final Reservation reservation) {
        log.error("[ PaymentService - Rollback] Reservation {} ", Thread.currentThread().getId());
        log.error("[ PaymentService - Rollback] Payment {} ", TransactionSynchronizationManager.getCurrentTransactionName());

        ReservationPaymentEvent reservationPaymentEvent = new ReservationPaymentEvent(reservation, payment);
        Outbox outbox = outboxMapper.toOutboxEvent(reservationPaymentEvent);
        outboxRepository.save(outbox);
    }
}

```

@Transactional(propagation = REQUIRES_NEW) 속성을 통해 기존 트랜잭션이 아닌 새로운 트랜잭션을 시작하도록 보장할 수 있다.

기존의 `ReservationService.createMyReservation` 트랜잭션은 이미 롤백이 결정된 상태이므로 만약 `registerFailPayment`가 기존 트랜잭션에 참여한다면, 실패 기록을 저장하는 로직( `outboxRepository.save(outbox)` ) 또한 함께 롤백되어 아무런 정보도 남지 않게 된다.

하지만 `REQUIRES_NEW` 전파 옵션 덕분에, 기존 트랜잭션은 잠시 보류되고, 완전히 새로운 트랜잭션이 시작되어 `outbox` 테이블에 실패 기록을 저장합니다. 이 새로운 트랜잭션은 기존 트랜잭션의 롤백 여부와 관계없이 독립적으로 커밋된다. 이로써 우리는 어떠한 DB 장애 상황에서도 고아 결제 정보를 절대 유실하지 않고 안정적으로 기록할 수 있다.

이러한 방식을 **트랜잭셔널 아웃박스(Transactional Outbox) 패턴**이라고도 부른다. 이벤트(여기서는 '결제 실패 이벤트')를 별도의 테이블(outbox)에 원자적으로 저장한 뒤, 별도의 프로세스가 이 테이블을 읽어 후속 조치를 취하는 패턴이다.

### **스케줄러를 이용한 고아 결제 복구**

```java
@Component
public class PaymentRecoveryScheduler {

    private final OutboxRepository outboxRepository;
    // ... 필요한 서비스 주입 ...

    @Scheduled(fixedDelay = 60000) // 1분마다 실행
    @Transactional
    public void recoverFailedPayments() {
        List<Outbox> failedPayments = outboxRepository.findAllByStatus(PENDING);
        for (Outbox outbox : failedPayments) {
            try {
                // outbox의 정보를 바탕으로 Payment, Reservation 테이블에 데이터를 저장하는 로직 수행
                // ...
                // 성공 시 outbox 레코드 상태를 PROCESSED로 변경하거나 삭제
                outbox.process();
            } catch (Exception e) {
                // 재처리 중 예외 발생 시 로그 기록 및 에러 모니터링
            }
        }
    }
}

```

스케줄러는 주기적으로 `outbox` 테이블에서 처리되지 않은(PENDING) 실패 기록들을 조회하여 기록을 바탕으로, 원래 저장되었어야 할 결제 정보와 예약 정보를 DB에 다시 저장한다.

처리가 성공적으로 완료되면, 해당 `outbox` 레코드의 상태를 변경하거나 삭제하여 중복 처리를 방지한다.

---

### 결론

최종적으로 비즈니스 로직의 **원자성**은 유지하면서, 장애 상황에서도 **데이터 유실을 방지**하고, 최종적으로 **궁극적 일관성(Eventual Consistency)** 을 달성하였다.

즉시 취소 대신 트랜잭션 동기화 + 아웃박스 패턴 조합으로 결제 시스템에서 발생할 수 있는 불일치 문제를 안정적으로 해결하면서, 사용자 경험과 운영 효율성을 모두 지킬 수 있었다.
