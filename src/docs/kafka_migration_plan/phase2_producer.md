# Phase 2: Producer 전환 (FailedPaymentRegister → Kafka)

**시작 전에 `00_overview.md`를 먼저 읽을 것.** Phase 0 설계 결정(토픽명, payload, acks, 클래스명/패키지
고정값)을 그대로 따른다. 특히 "구현 일관성 고정값" 표는 임의로 바꾸지 말 것 — 다른 Phase가 그 값을 전제한다.

## 선행 조건

Phase 1 완료 — Kafka가 로컬에서 기동되고, `payment.persist.failed`/`.DLT` 토픽이 선언돼 있고
Spring Boot에서 `KafkaTemplate`을 쓸 수 있는 상태.

**`phase1_kafka_infra.md` 하단 "Phase 1 결과"를 먼저 읽을 것.** 토픽명 상수 위치, 하네스 사용법
(`consumeFromAnEmbeddedTopic` 사용 불가), 그리고 🔴 **전역 직렬화 설정을 바꾸면 `KafkaSmokeTest`가 깨진다**는
주의가 있다.

## 목표

결제 승인(tx3) 롤백 시 Outbox 테이블에 기록하던 것을, Kafka `payment.persist.failed` 토픽에
직접 publish하는 것으로 교체한다.

**이 전환의 핵심 이유를 잊지 말 것:** 기존 방식은 `@Transactional(REQUIRES_NEW)`로 **같은 DB에**
복구 레코드를 쓰려 했기 때문에, DB 장애(= tx3 롤백의 주 원인)에서 함께 실패했다. 복구 기록을 실패한
자원 밖으로 빼는 것이 목적이다. (`00_overview.md` 한계 1)

## 건드릴 코드

- `src/main/java/roomescape/payment/service/PaymentService.java` — `completePayment()`
  (66~74행 `TransactionSynchronization.afterCompletion`). **이 호출 지점 구조는 유지**하고,
  주입받는 협력 객체를 `FailedPaymentRegister` → `PaymentPersistFailedPublisher`로 교체한다.
- `src/main/java/roomescape/payment/outbox/FailedPaymentRegister.java` — Phase 4에서 삭제될 예정.
  여기서는 **신규 패키지에 대체 클래스를 만들고 참조를 끊는 것**까지 한다.

## 신규 작성 (고정값 — 00_overview.md "구현 일관성 고정값" 표)

- 패키지: `roomescape.payment.event` (기존 `payment.outbox`는 Phase 4 삭제 대상이므로 재사용 금지)
  - 이벤트 record와 Publisher만 둔다. 컨슈머 쪽(`payment.recovery`)은 Phase 3에서 만든다.
    `PaymentService`가 `recovery`를 import하게 되면 잘못된 것이다 — 발행하는 쪽은 누가 소비하는지 몰라야 한다.
- Payload: `PaymentPersistFailed(Long paymentId)` record
- Producer: `PaymentPersistFailedPublisher`

## 할 일

0. **선행: `Payment.getId()` 추가.** 현재 `Payment`에는 id getter가 없다. 키와 payload가 `paymentId`라서 필요하다.
1. `PaymentPersistFailed` record 작성 — **`paymentId` 하나만.** 엔티티를 담지 않는다.
   - tx1에서 예약과 `Payment(IN_PROGRESS)`는 이미 커밋돼 있으므로 Consumer가 `paymentId`로 재조회할 수 있다.
     `reservationId`·`paymentKey`는 `Payment` row에 있으므로 싣지 않는다 (체크리스트 6번).
   - 이벤트 이름은 사실 기반이라 "PG 승인은 성공했다"는 전제가 드러나지 않는다.
     **"PG 승인 성공 후에만 발행된다"는 것을 record 주석에 명시**한다 (체크리스트 2번).
2. `PaymentPersistFailedPublisher` 작성 — `KafkaTemplate<String, PaymentPersistFailed>`로
   `payment.persist.failed`에 publish.
   - 키는 `paymentId`(String 변환).
   - Producer 설정: `acks=all`, **`enable.idempotence=true` 명시** (체크리스트 18번).
   - 직렬화는 `JsonSerializer`. **타입 헤더를 끈다**(`spring.json.add.type.headers=false`) — 클래스 전체 이름이
     헤더에 들어가면 나중에 이름·패키지를 바꿀 때 옛 메시지를 못 읽는다 (체크리스트 21번).
   - **`@Transactional(REQUIRES_NEW)`를 붙이지 않는다.** Kafka publish는 로컬 DB 트랜잭션이 아니고,
     애초에 그 애노테이션이 한계 1의 원인이었다.
3. 🔴 **`send()`의 반환 `CompletableFuture`를 반드시 `whenComplete`로 받아서, 실패 시 CRITICAL 로그를
   남긴다.**
   - `send()`는 **비동기**다. 반환값을 버리면 브로커 장애 시 publish 실패가 **무음으로 유실**된다 —
     기존 코드가 `afterCompletion`에서 예외를 삼켜 아무 흔적도 남기지 않았던 실수의 반복이 된다.
   - 로그에는 `paymentId`를 반드시 포함할 것. 그 로그가 마지막 단서다.
   - 블로킹(`.get(timeout)`)은 하지 않는다 — 2차 방어선(정산 스윕)이 설계에 있으므로 요청 스레드를
     잡는 비용을 택하지 않았다. (`00_overview.md` Phase 0 "발행 완료 대기" 참고)
4. `PaymentService`가 새 publisher를 주입받도록 수정하고, `FailedPaymentRegister` 참조를 끊는다.
   - **과도기 공존(Outbox 저장 + Kafka publish 동시)은 하지 않는다.** 기존 복구 경로는
     `@EnableScheduling` 누락으로 **현재 0% 동작**이므로 지킬 것이 없다. (`00_overview.md` 한계 2)
   - `FailedPaymentRegister`/`ReservationPaymentEvent` 파일 자체의 삭제는 Phase 4에서 일괄 처리한다.

## TDD 진행 순서 (RED → GREEN)

**각 단계마다 테스트를 먼저 쓰고, 실패하는 것을 확인한 뒤 구현한다.** 위 "할 일"은 무엇을 만들지,
이 순서는 어떤 순서로 만들지다. (00_overview.md "개발 방식: TDD")

**단위 테스트** — Kafka 없음, `KafkaTemplate` mock. ms 단위로 돈다.

1. `PaymentPersistFailedPublisher`가 `payment.persist.failed` 토픽에 키 `paymentId`, payload
   `PaymentPersistFailed(paymentId)`로 `send()`를 호출한다.
   - 첫 RED는 컴파일 실패다(record·publisher 클래스가 없음). 정상이다.
2. `send()`가 반환한 future가 실패로 끝나면 CRITICAL 로그가 `paymentId`와 함께 남는다.
   - `OutputCaptureExtension`으로 로그를 검증한다.
   - 🔴 이 테스트가 할 일 3번(`whenComplete`)의 유일한 증거다. 이 테스트 없이 넘어가지 말 것.

**통합 테스트** — `@KafkaIntegrationTest`(Phase 1 하네스).

3. 실제 브로커로 발행된 메시지를 읽어서 키가 `paymentId`이고, payload가 `paymentId`만 담고 있고,
   `__TypeId__` 타입 헤더가 없는 것을 확인한다. (직렬화 설정은 단위 테스트로 잡을 수 없다)
4. `PaymentService.completePayment`에서 `paymentRepository.save`가 실패하도록 만들면(tx3 롤백),
   `payment.persist.failed`에 메시지가 도착한다. Awaitility로 기다린다.
   - `afterCompletion` 연결이 제대로 됐는지 보는 테스트다.

`Payment.getId()`는 단순 getter라 별도 테스트를 두지 않는다 — 1·3·4번이 간접적으로 검증한다.

## 완료 기준

- 위 TDD 순서의 테스트 4개가 모두 통과한다.
- tx3 롤백을 유도하는 테스트에서 `payment.persist.failed` 토픽에 실제로 메시지가 발행되는 것을
  확인한다.
  - `PaymentService.completePayment`에서 `paymentRepository.save`가 실패하도록 mock
    (`ReservationIntegrationTest`에 유사 패턴 있음).
  - 검증 환경은 **`@EmbeddedKafka`** (00_overview.md 고정값 — Testcontainers 쓰지 말 것).
- 메시지 키가 `paymentId`이고, payload가 `paymentId`만 담고 있는 것을 확인한다 (엔티티 직렬화 흔적 없음,
  `__TypeId__` 타입 헤더 없음).
- **publish 실패 경로도 확인한다** — 잘못된 `bootstrap-servers`나 mock된 `KafkaTemplate`으로
  실패를 유도해, CRITICAL 로그가 실제로 찍히는지 본다. 이게 안 되면 3번이 구현된 게 아니다.

## 이번 Phase에서 하지 않는 것

- 메시지를 소비하는 Consumer, 에러 핸들러, DLQ 설정 → Phase 3
- 기존 Outbox 파일/테이블 삭제 → Phase 4
- `payment.status=IN_PROGRESS` 정산 스윕 → 범위 밖 (00_overview.md)