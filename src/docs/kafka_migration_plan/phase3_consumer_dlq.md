# Phase 3: Consumer + 재시도 + DLQ

**시작 전에 `00_overview.md`를 먼저 읽을 것.** 특히 **"실패 모드 분석"** 섹션 — 이 Phase의 재시도/DLQ
정책이 전부 거기서 도출된다. 정책의 숫자만 보고 고치지 말 것.

## 선행 조건

Phase 2 완료 — `payment.persist.failed` 토픽에 `PaymentPersistFailed`가 실제로 쌓이는 상태.

## 목표

`payment.persist.failed`를 소비해서 결제 완료 처리를 재시도하는 Consumer를 만들고, 재시도가
실패하면 DLQ(`payment.persist.failed.DLT`)로 보낸다.

## 먼저 이해할 것: 재시도와 DLQ의 역할 분담

`00_overview.md` 실패 모드 분석의 결론을 그대로 가져온다:

| 실패 모드 | 지속 시간 | 3회 재시도로 복구되는가 |
|---|---|---|
| 일시적 커넥션 블립, DB 락 경합 | 수 초 | **된다** ← 재시도가 담당하는 유일한 구간 |
| **DB 장애 (주 실패 모드)** | 수 분~수십 분 | **안 된다** — Consumer도 같은 DB를 재조회해야 함 |
| 영구 결함 (역직렬화 실패, Payment 미존재, 코드 버그) | 영구 | 안 된다 — **재시도 없이 즉시 DLQ** |

> **DLQ는 드문 예외 창구가 아니다. DB 장애 중 들어온 복구 건의 "정상 착지점"이다.**

그래서 재시도 횟수를 늘리는 것은 의미가 없다 (게다가 `DefaultErrorHandler`는 **블로킹 재시도**라
백오프를 길게 주면 그 파티션 전체가 정체된다). 대신 **DLQ 재투입 절차를 반드시 설계**해야 한다 —
그게 이 Phase에서 가장 중요한 산출물이다.

## 건드릴 코드 (신규 작성 위주)

- 신규 `@KafkaListener` 컴포넌트 — 패키지는 **`roomescape.payment.recovery`**. 이벤트 record는
  Phase 2에서 만든 `roomescape.payment.event`의 것을 import한다. 의존 방향은 `recovery → event`만 허용
  (00_overview.md 고정값.
  `payment.outbox`는 Phase 4 삭제 대상이므로 쓰지 말 것).
- Kafka 설정 클래스에 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 등록.

## 참고할 기존 로직

현재 `OutboxProcessor.processFailedPayments()` (`src/main/java/roomescape/common/outbox/OutboxProcessor.java`)가
하던 일을 참고하되 **그대로 베끼지 않는다.** 기존 코드는 역직렬화한 `Reservation`/`Payment` 엔티티를
그대로 `save()`해서 전체 스냅샷을 덮어썼지만, Phase 2에서 payload를 최소 식별자로 바꿨다.

**선행: `Payment.isCompleted()` 추가.** 현재 `Payment`에는 상태를 읽을 방법이 없다. 상태 getter를
노출하지 말고 `isCompleted()`로 캡슐화를 유지한다 (체크리스트 9번).

Consumer 로직:

1. 메시지에서 `paymentId` 추출.
2. `paymentRepository.findById(paymentId)`로 Payment 조회.
   - **없으면 커스텀 예외를 던진다.** tx1이 커밋됐으면 반드시 있어야 하므로, 없다는 건 정합성 이상이다.
     이 예외는 재시도 불가로 등록해 바로 DLT로 보낸다(할 일 2번).
3. **멱등성 체크**: `payment.isCompleted()`면 할 일이 없다 — skip하고 정상 종료.
   - Kafka는 at-least-once라 중복 전달이 가능하고, **DLQ 재투입 시에도 반드시 필요하다.**
4. 완료되지 않았으면 `payment.complete()` 후 저장.

이 로직은 `@KafkaListener` 메서드에 직접 넣지 말고 **별도 핸들러 클래스**로 둔다. 메인 리스너와
DLQ 재투입 리스너(할 일 5번)가 같은 핸들러를 호출한다.

※ `reservationRepository.save(...)`는 하지 않는다 — 예약은 tx1에서 이미 커밋돼 있다.

## 할 일

1. Consumer(`@KafkaListener`) 구현 — 위 로직. 컨슈머 설정은 Phase 0 표를 따른다:
   - `group.id`: `roomescape-payment-recovery` (체크리스트 16번)
   - `auto.offset.reset`: **`earliest`** — `latest`면 그룹이 처음 뜰 때 쌓인 복구 메시지를 건너뛴다 (15번)
   - concurrency: **1** (19번)
   - 역직렬화: **`ErrorHandlingDeserializer`가 `JsonDeserializer`를 감싸게** 하고, 타입 헤더 대신
     `spring.json.value.default.type`으로 `PaymentPersistFailed`를 지정한다 (21·23번)
2. 에러 핸들러: `DefaultErrorHandler` + `FixedBackOff`, **3회**.
   - 역할은 "일시적 블립 흡수"로 한정. 백오프 간격을 분 단위로 늘리지 말 것 — 블로킹 재시도라
     파티션이 정체되고, 그래도 DB 장애는 못 버틴다.
   - **재시도 불가 예외**: Payment 미존재 커스텀 예외를 `addNotRetryableExceptions`에 등록한다.
     역직렬화 실패(`DeserializationException`)는 기본적으로 재시도 불가로 분류돼 있다 —
     실제로 그런지 테스트로 확인할 것 (체크리스트 11·23번).
3. 재시도 소진 시 `DeadLetterPublishingRecoverer`로 `.DLT`에 자동 전송되도록 설정.
   - DLT는 Phase 1에서 **원본과 같은 파티션 수(1)**로 선언돼 있어야 한다. 기본 destination resolver가
     **원본과 같은 파티션 번호로** 보내므로, DLT 파티션이 더 적으면 전송이 실패한다. Phase 1이
     안 돼 있으면 먼저 확인할 것.
4. DLQ 도달 시 CRITICAL 로그 — `paymentId`와 원인 예외를 포함.
   (운영 알림 연동은 범위 밖, 로그만)
5. 🔴 **DLQ 재투입 경로를 만들고 문서화한다.** 이번 Phase의 핵심 산출물이다.
   - 구현 방식: `.DLT`를 구독하는 `@KafkaListener(autoStartup = "false")` 리스너를 하나 둔다.
     `group.id`는 `roomescape-payment-recovery-dlt-replay`로 메인과 분리한다 (체크리스트 16번).
   - DB가 복구된 뒤 운영자가 `KafkaListenerEndpointRegistry`로 **수동으로 기동**하고, 다 처리하면 멈춘다.
   - **원본 토픽으로 되돌리지 않고, 메인 리스너와 같은 핸들러로 직접 처리한다.** 되돌리면 왕복 횟수를
     세는 로직이 필요해진다. 직접 처리하다 또 실패하면 메시지가 DLT에 그대로 남아 사람이 다시 본다
     (체크리스트 12번).
   - **일회성 스크립트/CLI는 쓰지 않는다.** TDD가 필수인데 스크립트는 테스트를 쓸 수 없다.
   - 멱등성 체크(3번)가 있으므로 중복 재투입은 안전하다.
   - **자동 재처리 루프는 만들지 않는다** — DB가 아직 죽은 상태에서 자동 재투입하면 DLQ ↔ 원본
     토픽을 무한 왕복한다. 사람이 "DB 복구됨"을 판단한 뒤 트리거하는 것이 이 설계의 전제다.
   - 재투입 절차는 README나 이 파일 하단에 단계별로 적어두고, Phase 6 문서에서 참조한다.

## TDD 진행 순서 (RED → GREEN)

**각 단계마다 테스트를 먼저 쓰고, 실패하는 것을 확인한 뒤 구현한다.** 이 Phase가 TDD의 본무대다 —
분기가 가장 많다. (00_overview.md "개발 방식: TDD")

**단위 테스트** — Kafka 없음. 분기 로직은 전부 여기서 잡는다.

1. `PaymentTest`(신규): `IN_PROGRESS`인 Payment는 `isCompleted()`가 false, `complete()` 후에는 true.
   - 현재 `payment/model` 테스트가 없으므로 이게 이 Phase의 첫 RED다.
2. 핸들러: `isCompleted()`인 Payment면 **저장하지 않는다** (repository mock으로 `save` 미호출 검증).
3. 핸들러: 완료되지 않은 Payment면 `complete()` 후 저장한다.
4. 핸들러: `paymentId`에 해당하는 Payment가 없으면 커스텀 예외를 던진다.

**통합 테스트** — `@KafkaIntegrationTest`. 배선만 검증한다.

5. 정상 소비: 메시지를 발행하면 DB의 Payment가 `COMPLETED`가 된다 (Awaitility).
6. 재시도 후 DLT: 처리가 계속 실패하게 만들면(예: `@MockitoSpyBean`으로 repository가 예외를 던지게) 3회 재시도 뒤
   `.DLT`에 도착한다.
7. 재시도 불가 → 즉시 DLT:
   - 역직렬화가 실패하는 메시지(깨진 JSON)를 보내면 **핸들러가 한 번도 호출되지 않고** `.DLT`에 도착한다.
   - 없는 `paymentId`를 보내면 **핸들러가 1번만 호출되고** `.DLT`에 도착한다.
   - "재시도 없이"는 핸들러 호출 횟수로 검증한다. 도착만 확인하면 3회 재시도 후 도착한 것과 구별되지 않는다.
8. 재투입: `.DLT`에 쌓인 메시지를 재투입 리스너를 기동해 처리하면 Payment가 `COMPLETED`가 된다.
   - 재투입 리스너는 `autoStartup = "false"`이므로 테스트에서 `KafkaListenerEndpointRegistry`로 직접 켠다.
   - 🔴 이 테스트가 없으면 DB 장애 시 설계가 작동한다는 증거가 없다.

※ Phase 1에서 넘어온 확인 항목: 브로커 auto-create를 껐으므로 **선언하지 않은 토픽명으로 보내면 에러**가 나야 한다
(체크리스트 20번). 아직 실제로 시험하지 않았다. 위 통합 테스트를 쓰다가 토픽명이 틀렸을 때 조용히 통과하지 않는지
한 번 확인할 것. 하네스 사용법과 주의점은 `phase1_kafka_infra.md` 하단 "Phase 1 결과" 참고.

## 완료 기준

- 위 TDD 순서의 테스트 8개가 모두 통과한다.
- 정상 케이스: 발행된 메시지가 Consumer에서 처리되어 Payment가 `COMPLETED`로 바뀐다.
- 멱등성 케이스: 이미 `COMPLETED`인 Payment에 같은 메시지를 다시 보내도 에러 없이 skip된다.
- 실패 케이스: 처리 로직이 계속 실패하도록 만든 테스트에서 `.DLT`에 메시지가 도달한다.
- 재시도 불가 케이스: 역직렬화가 실패하는 메시지와 존재하지 않는 `paymentId`가 **재시도 없이** `.DLT`에 도달한다.
- **재투입 케이스: `.DLT`에 있는 메시지를 재투입해서 최종적으로 `COMPLETED`가 되는 것을 확인한다.**
  (5번이 동작하지 않으면 DB 장애 시 설계가 작동하지 않는다는 뜻이다)
- 재투입 절차가 글로 적혀 있다.
- 테스트 환경은 **`@EmbeddedKafka`** (00_overview.md 고정값).

## 이번 Phase에서 하지 않는 것

- 기존 Outbox 코드/테이블 삭제 → Phase 4
- DLQ **자동** 재처리 루프, 운영 알림 연동(Slack 등) → 범위 밖 (00_overview.md)
- `payment.status=IN_PROGRESS` 정산 스윕 → 범위 밖 (다음 트랙)