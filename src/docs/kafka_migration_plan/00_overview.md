# 결제 복구 플로우: Outbox → Kafka + DLQ 전환 개요

이 디렉토리의 각 `phaseN_*.md`는 **별도의 신규 Claude Code 세션에서 독립적으로 진행**하는 것을 전제로 작성했다.
Phase를 맡은 세션은 이 문서(00_overview.md)와 해당 phase 문서를 먼저 읽고 시작할 것.
설계 결정의 **근거 원본**은 `src/docs/kafka_decision_checklist.md`다. Phase 0 표는 그 요약이다.

이 작업은 `src/docs/technical_decision/05_orphan-payment-handling.md`(고아 결제 3단계 복구 전략)의
후속이다. 배경 맥락이 더 필요하면 그 문서를 먼저 읽을 것.

## 배경

결제 승인(Toss PG) 후 DB 저장이 실패하는 구간을 "취소"가 아니라 "복구"로 설계했다.
방탈출은 시간대별 좌석이 1개뿐이라, 결제를 취소하고 재예약을 유도하면 그 사이 다른 사람이
자리를 가져갈 수 있어 보상 트랜잭션(결제 취소)을 기각했다. 대신 PG 승인은 유지하고,
DB 저장 실패 건만 별도로 기록해 재시도하는 구조를 선택했다.

### 한계 1: 같은 DB에 쓰는 Outbox는, 정작 그 장애에서 함께 실패한다 (구조적 결함)

현재 `Outbox`는 `@Table(name = "outbox_event")`로 **업무 DB와 같은 datasource**에 저장된다
(`src/main/java/roomescape/common/outbox/Outbox.java`). 그런데 tx3 롤백의 주 원인은 DB 장애다.
연쇄가 이렇게 된다:

```
1. DB 장애 → tx3(PaymentService.completePayment) 롤백
2. afterCompletion 콜백 → failedPaymentRegister.registerFailPayment(...) 호출
3. @Transactional(REQUIRES_NEW)가 "같은 죽은 DB"에 새 커넥션을 요청 → 실패
4. afterCompletion에서 던진 예외는 Spring이 삼킨다 → 로그 한 줄도 남지 않음
```

**복구 레코드가 가장 필요한 순간에 복구 레코드를 쓸 수 없고, 그 사실조차 무음으로 사라진다.**
PG 승인은 났으니 돈은 빠져나갔는데 시스템에 아무 흔적이 없다.

이건 설정 실수가 아니라 구조적 한계다. Transactional Outbox 패턴은 원래 "업무 커밋은 성공했는데
메시지 발행이 실패"하는 경우를 막는 패턴이라 **DB가 살아 있는 것을 전제**한다. 우리 실패 모드는
DB가 죽은 것이므로 패턴의 전제 자체가 맞지 않는다.

→ **따라서 복구 기록은 "실패한 자원 밖"에 있어야 한다.** 이 전환의 출발점이다.

### 한계 2: 재시도 메커니즘이 자가 구현이고, 조용히 죽어 있었다

`OutboxProcessor.retryFailedPayments()`(`src/main/java/roomescape/common/outbox/OutboxProcessor.java`)에는
결함이 두 겹으로 쌓여 있다.

1. **`@Scheduled`가 아예 실행되지 않는다.** 프로젝트 전체에 `@EnableScheduling`이 없다
   (`grep -rn "EnableScheduling" src/` → 없음). Spring Boot는 스케줄링을 자동 활성화하지 않으므로
   이 스케줄러는 **단 한 번도 돈 적이 없다.**
2. 설령 돌았더라도 `OutboxStatus.PENDING`만 조회한다. 1회 실패 시 `Outbox.markFailed()`로 상태가
   `FAILED`가 되는데 `FAILED`를 다시 조회하는 코드가 없다. 즉 "N번 재시도 후 포기"가 아니라
   **"1번 실패하면 영구 방치"**다.

여기에 최종 실패 건을 격리하거나 운영자에게 알리는 경로도 없다.

핵심은 개별 버그가 아니라 **재시도 인프라(상태 머신 + 폴링 + retryCount 상한 + 가시성)를 직접 만들어
소유했다는 것**이다. 직접 만든 인프라는 이렇게 조용히 죽고, 아무도 모른다. 위 ①은 그 실증 사례다.

### 그래서: 복구 기록을 DB 밖으로 빼는 두 가지 선택지

| 선택지 | 작동하는가 | 비용 |
|---|---|---|
| **A. 복구 전용 별도 DB(별도 datasource)** | 업무 DB 장애와 운명을 분리하므로 **작동한다** | 복구 용도로**만** 쓰는 DB를 상시 운영해야 한다 |
| **B. 이미 보유한 MQ(Kafka)에 위임** | 동일하게 작동한다 | **추가 도입 비용 0**(이벤트 드리븐 조직은 이미 MQ 보유) + 재시도·상한·DLQ가 표준으로 딸려온다 |

**B를 채택한다.** 한계 1이 요구하는 "실패한 자원 밖에 기록"과 한계 2가 요구하는 "재시도 상한 +
최종 실패 가시성"을 **한 번에, 자가 구현 없이** 얻을 수 있는 쪽이기 때문이다. A는 전자만 해결하고
후자는 여전히 직접 만들어야 한다.

A를 기각한 이유는 Phase 6 문서에 **검토 후 기각한 대안으로 명시**할 것. "왜 Kafka인가"는 유행이
아니라 위 실패 모드 분석의 결론이어야 한다.

## 실패 모드 분석 (모든 정책이 여기서 도출된다)

Phase 0 이하의 모든 정책은 이 분석에서 나온다. 정책을 바꾸고 싶으면 먼저 이 분석을 의심할 것.

| 실패 모드 | 지속 시간 | Consumer 3회 재시도로 처리되는가 |
|---|---|---|
| 일시적 커넥션 블립, DB 락 경합 | 수 초 | **처리된다** — 짧은 재시도가 흡수하는 유일한 구간 |
| **DB 장애 (주 실패 모드)** | **수 분~수십 분** | **처리되지 않는다** — Consumer도 같은 DB를 재조회해야 한다. 3회가 수 초 안에 소진되고 DLQ로 이동 |
| 영구 결함 (역직렬화 실패, Payment 미존재, 코드 버그) | 영구 | 재시도하지 않는다 — 재시도 불가 예외로 분류해 **즉시 DLQ로 이동** |

**중요: "DLQ로 이동"은 유실이 아니다.** Kafka 토픽은 소비됐다고 지워지지 않고, `.DLT`에 durable하게
남는다. 즉 **데이터는 안전하고, "자동으로 이어서 처리되지 않는다"는 것만이 사실이다.**

여기서 나오는 결론:

> DLQ는 드문 예외 창구가 아니라, **DB 장애 중 들어온 복구 건의 정상 착지점**이다.
> 재시도 3회의 역할은 "일시적 블립 흡수"로 한정하고, 지속 장애는 DLQ에 모아
> **DB 복구를 확인한 뒤 통제된 시점에 재투입**한다.

### 왜 "무한 재시도로 자동 재개"를 택하지 않았나 (검토 후 기각)

재시도를 길게/무한으로 가져가면 오프셋이 커밋되지 않으므로, DB가 복구되는 순간 **자동으로 이어서
처리**된다. 운영 개입이 0이라는 분명한 장점이 있다. 그래도 (ㄱ) 짧은 재시도 + DLQ + 수동 재투입을
택한 이유:

1. **DB 복구 시점을 시스템이 판단할 수 없다.** 커넥션은 되지만 느리거나 간헐 실패하는 "반쯤 살아난"
   상태에서 자동 재시도가 계속 돌면, 회복 중인 DB에 **재시도 폭풍**을 얹는다. 사람이 복구를 확인한
   뒤 재투입하는 쪽이 안전하다.
2. **poison pill 위험.** 제약 조건 위반처럼 절대 성공할 수 없는 메시지 하나가 무한 재시도에 걸리면,
   뒤에 줄 선 모든 복구 건을 **영구히 막는다.** (참고: 파티션 정체 자체는 DB 장애 상황에서 큰 비용이
   아니다 — 뒤 메시지도 어차피 같은 DB를 쓰므로 처리 불가다. 진짜 위험은 영구 결함 쪽이다.)
3. 어차피 유실이 없으므로, 자동 재개의 이득이 "운영 편의"에 그친다. 이 규모에서 1·2의 위험과
   바꿀 만하지 않다.

예외 타입 분류는 **절반만 채택한다.** 절대 성공할 수 없는 예외(역직렬화 실패, Payment 미존재)는
재시도 없이 즉시 DLQ로 보낸다(체크리스트 11·23). 나머지 절반인 "일시적 DB 예외는 길게 재시도해서
자동 재개"는 위 1번 이유로 채택하지 않는다.
`@RetryableTopic` 지연 재시도 토픽 체인도 같은 이유로 기각(토픽 3~4개 증가).

🔴 **(ㄱ)을 택한 대가: `.DLT` retention이 유실 방지선이 된다.** 수동 재투입이 전제이므로, 사람이 늦으면
메시지가 retention 만료로 사라진다. `retention.ms` 기본값(7일)에 의존하지 말고 **30일로 명시**한다
(Phase 0 표 참고).

### 복구 정보의 이중화

Kafka publish도 `afterCompletion`에서 일어나므로, 브로커 장애나 그 순간의 JVM 사망 시 메시지는
유실될 수 있다. 그때 남는 것은 tx1에서 **이미 커밋된** `payment.status = IN_PROGRESS` 행이다.
(이미 커밋됐으므로 DB 장애에도 안전하다 — 장애 중에 새로 써야 하는 Outbox 레코드와 결정적으로 다른 점.)

역할이 이렇게 갈린다:

| 경로 | 역할 | PG 재조회 필요? |
|---|---|---|
| Kafka 메시지 | **1차 경로.** "PG 승인이 성공했다"는, DB 어디에도 없는 정보를 실패한 자원 밖에 보존 | 불필요 |
| `payment.status=IN_PROGRESS` 정산 스윕 | **2차 방어선(최후).** 메시지까지 유실된 경우 | 필요 (승인 여부를 모름) |

→ **source of truth는 DB 상태이고, Kafka는 fast path다.** 이 한 줄이 설계의 등뼈다.
정산 스윕의 **구현은 이번 범위 밖**(다음 트랙)이지만, 설계상 필요하다는 사실은 Phase 6 문서에 남긴다.

## 현재 코드 구조 (전환 대상)

트랜잭션 흐름 (`ReservationApplicationService.createReservation`):

```
1. reservationService.createReservation()   [tx1: 예약 + Payment(IN_PROGRESS) 저장, 커밋됨]
   └─ ReservationService는 클래스 레벨 @Transactional, createInProgressPayment는 REQUIRED로 합류
2. paymentService.callPG()                  [트랜잭션 밖 — 외부 PG 호출]
   └─ 실패 시: reservationService.cancelReservation() 으로 보상, 예외 재throw
3. paymentService.completePayment()         [tx3: Payment → COMPLETED 전환 후 저장]
     └─ tx3 ROLLED_BACK 시 TransactionSynchronization.afterCompletion 콜백에서
        failedPaymentRegister.registerFailPayment(payment, reservation) 호출
          └─ [tx4: REQUIRES_NEW] Outbox 테이블에 ReservationPaymentEvent 직렬화 저장
             ※ 한계 1 — DB 장애 시 이 tx4도 같이 실패하고, 예외는 삼켜진다
```

관련 파일:
- `src/main/java/roomescape/reservation/service/ReservationApplicationService.java` — 전체 오케스트레이션
- `src/main/java/roomescape/payment/service/PaymentService.java` — `completePayment()` (66~74행에 TransactionSynchronization)
- `src/main/java/roomescape/payment/outbox/FailedPaymentRegister.java` — 전환 대상 (Kafka Producer로 교체)
- `src/main/java/roomescape/payment/outbox/ReservationPaymentEvent.java` — 전환 대상 (payload 축소)
- `src/main/java/roomescape/common/outbox/OutboxProcessor.java` — 전환 대상 (Kafka Consumer로 교체, 이후 삭제)
- `src/main/java/roomescape/common/outbox/{Outbox,OutboxRepository,OutboxMapper,OutboxStatus}.java` — Phase 4에서 삭제 대상
- `src/main/java/roomescape/common/OutboxEventType.java` — 실질적으로 거의 미사용(값이 할당되는 곳이 없음). Phase 4에서 같이 정리
- `src/test/java/roomescape/reservation/ReservationIntegrationTest.java` — `createReservation_PaymentSuccess_DBFail_OutboxCreated` 테스트가 **이 작업 시작 전부터 이미 실패 중**(이번 작업이 만든 회귀 아님, 기존 결함). Phase 4에서 **삭제**하고 Phase 5에서 Kafka 기준 테스트를 새로 작성한다 (원인 디버깅은 하지 않는다 — 아래 Phase 4 참고).

## Phase 0: 확정된 설계 결정 (모든 Phase가 따를 전제)

이 표는 구현용 요약이다. **결정 근거의 원본은 `src/docs/kafka_decision_checklist.md`**(이하 체크리스트)이고,
괄호 안 번호는 체크리스트 항목 번호다. 둘이 어긋나면 체크리스트를 먼저 고치고 이 표를 맞춘다.

#### 토픽

| 항목 | 결정 | 이유 |
|---|---|---|
| 토픽 | `payment.persist.failed` (3) | 복구 이벤트 종류별로 별도 토픽. 재시도·DLQ 정책이 리스너(=토픽) 단위로 붙기 때문. 이름은 이벤트와 같은 사실 기반 — 토픽은 무슨 일이 있었는지, `group.id`·패키지는 누가 무엇을 하는지(복구)를 나타낸다 |
| DLQ 토픽 | `payment.persist.failed.DLT` | Spring Kafka `DeadLetterPublishingRecoverer`의 기본 네이밍(원본 토픽명 + `.DLT`) |
| 파티션 수 | **원본·DLT 모두 1개**, replication factor 1 (5) | 파티션은 1 → N 증설만 가능하고 축소는 불가하므로 1이 되돌리기 쉬운 선택. 처리량 극저, 순서 불필요. `DeadLetterPublishingRecoverer`가 **원본과 같은 파티션 번호로** 보내므로 DLT도 같은 수여야 한다 |
| retention / cleanup | 원본 **7일**(기본값), DLT **30일 명시**. 둘 다 `delete` (17) | 한 번 처리하면 끝나는 **작업 메시지**라 `compact`는 맞지 않음. DLT는 사람을 기다리는 곳이라 길게 — (ㄱ) 수동 재투입 설계의 유실 방지선 |
| 토픽 생성 | `@Bean NewTopic`으로 원본·DLT **둘 다 선언**, 로컬 브로커는 **auto-create 끔** (20) | 파티션 수·retention이 코드에 남아 재현됨. auto-create를 끄면 토픽명 오타 시 조용히 새 토픽이 생기지 않고 에러가 남 |

#### 메시지

| 항목 | 결정 | 이유 |
|---|---|---|
| 이벤트 | **`PaymentPersistFailed`** — 사실 기반 네이밍 (2) | 복구는 이 사실에 대한 대응 정책이고, 정책은 컨슈머가 소유한다. **PG 승인 성공이 발행 전제조건**이라는 것은 이름에 없으므로 코드 주석에 명시 |
| 키 | **`paymentId`** (4) | 우리 소유의 불변 ID. PG가 여러 개라 `paymentKey`는 PG사 간 충돌 가능 |
| Payload | **`PaymentPersistFailed(Long paymentId)` 하나만** (6) | `reservationId`·`paymentKey`는 `Payment` row에 이미 있음. 필드는 추가만 허용(아래)이므로 최소로 시작하는 게 되돌리기 쉽다. tx1이 커밋돼 있어 재조회 가능 |
| 직렬화 | **JSON** (7) | Producer·Consumer가 같은 앱이라 Schema Registry 이득이 없음 |
| 스키마 진화 | 필드는 **추가만** 허용. **타입 헤더 끔**(`spring.json.add.type.headers=false`) + 컨슈머 기본 타입 지정(`spring.json.value.default.type`) (21) | DLT에 최대 30일 전 형식이 남으므로 backward 호환 필요. 타입 헤더에 클래스 전체 이름이 들어가면 이벤트명·패키지를 바꾸는 순간 옛 메시지를 못 읽는다 |

#### Producer

| 항목 | 결정 | 이유 |
|---|---|---|
| 발행 시점 | tx3 롤백 `afterCompletion`에서 **즉시** Kafka publish | 이미 DB 쓰기가 실패한 뒤라 "DB 커밋 + 메시지 발행 원자성" 문제(Outbox 패턴이 원래 풀려는 문제)가 여기엔 없음. 오히려 같은 DB에 쓰려 하면 한계 1에 걸림 |
| 신뢰성 | `acks=all`, **`enable.idempotence=true` 명시** (18). **`send()`의 `CompletableFuture`를 반드시 `whenComplete`로 받아** 실패 시 CRITICAL 로그 | `send()`는 비동기라 콜백이 없으면 publish 실패가 **무음 유실**된다. 멱등 Producer는 같은 세션 안의 재전송 중복만 막으므로(재시작 시 Producer ID 재발급) 최종 방어선은 컨슈머 멱등성 |
| 발행 완료 대기 | `whenComplete` 콜백만 사용하고 **블로킹하지 않는다** | 돈 경로라 `.get(timeout)` 블로킹도 후보였으나, 요청 스레드를 수백 ms 잡는 비용 대비 2차 방어선(정산 스윕)이 있으므로 비블로킹 선택. 이 trade-off는 Phase 6에 기록 |

#### Consumer

| 항목 | 결정 | 이유 |
|---|---|---|
| 전달 보장 / 오프셋 | **at-least-once**, **처리 후 커밋**(Spring Kafka 기본값) (8, 10) | 외부 DB에 쓰므로 Kafka exactly-once가 성립하지 않음. 유실은 허용 불가, 중복은 멱등성이 흡수 |
| `auto.offset.reset` | **`earliest`** (15) | 기본값 `latest`면 그룹이 처음 뜨거나 `group.id`가 바뀔 때 쌓인 복구 메시지를 건너뜀 = 결제 유실. `@EmbeddedKafka` 테스트의 간헐 실패도 막음 |
| group.id | 메인 `roomescape-payment-recovery`, 재투입 `roomescape-payment-recovery-dlt-replay` (16) | 생명주기가 다르고 lag을 따로 봐야 함. **운영 중 이름 변경 금지**(오프셋 초기화) |
| concurrency | **1**, `max.poll.interval.ms` 기본값 (19) | 파티션 1개라 동시 처리 상한이 1. 다중 인스턴스면 1대만 소비하고 나머지는 대기(자동 장애 조치) |
| 멱등성 | 처리 전 `Payment.isCompleted()` 확인, true면 skip (9) | 도메인에 이미 상태가 있어 가장 쌈. DLQ 재투입 시에도 필수 |
| 순서 | **보장 불필요** (13) | 예약 선점은 tx1에서 끝났고, 결제 1건 = 메시지 1건 |

#### 재시도·DLQ

| 항목 | 결정 | 이유 |
|---|---|---|
| 재시도 | **(ㄱ) 채택** — `DefaultErrorHandler` + `FixedBackOff`, **3회 / 역할은 "일시적 블립 흡수"로 한정** (11) | 실패 모드 분석 참고. 무한 재시도(자동 재개)는 회복 중 DB에 재시도 폭풍 + poison pill 영구 블로킹 위험으로 기각 |
| 재시도 불가 예외 | **역직렬화 실패**: `ErrorHandlingDeserializer`로 `JsonDeserializer`를 감쌈. **Payment 미존재**: 커스텀 예외 → `addNotRetryableExceptions`. 둘 다 **재시도 없이 즉시 DLT** (11, 23) | 재시도해도 결과가 같다. 역직렬화는 리스너 도달 전(`poll()` 단계)에 실패하므로 래퍼 없이는 에러 핸들러가 정상 처리하지 못함 |
| **DLQ의 위치** | **드문 예외 창구가 아니라, 지속 장애 시 복구 건의 정상 착지점** | 실패 모드 분석의 핵심 결론. DB 장애 시 전건이 여기로 온다는 것을 전제로 운영 설계 |
| DLQ 재처리 | CRITICAL 로그. `autoStartup = "false"` 재투입 리스너를 **운영자가 수동으로 켠다.** 원본 토픽으로 되돌리지 않고 **같은 핸들러로 직접 처리**한다. 절차는 Phase 3에서 문서화 (12) | DB 복구 시점은 사람이 판단. 자동화를 겹겹이 쌓으면 오히려 문제를 놓친다(DDIA). 스크립트는 테스트할 수 없어 기각. 직접 처리하면 왕복 횟수 카운트가 필요 없고, 또 실패하면 DLT에 남아 사람이 다시 본다 |
| 관측 지표 | ① DLT 적재 건수(0이 아니면 알림) ② 원본 발행 건수 ③ consumer lag — 1건 이상이 **5분 넘게 유지**되면 이상 (14) | 평시 0건인 토픽이라 건수 기준 lag 임계값은 의미 없음. 알림 연동은 범위 밖 |

#### 원칙

| 항목 | 결정 | 이유 |
|---|---|---|
| source of truth | **DB 상태(`payment.status`)가 원장, Kafka는 fast path** | 메시지 유실에도 복구 가능성이 남는 구조. 정산 스윕(2차 방어선)은 구현은 범위 밖, 설계는 문서화 |

### 구현 일관성 고정값 (각 Phase가 다른 선택을 하지 않도록)

각 Phase를 **독립 세션**에서 진행하므로, 세션마다 다르게 고를 수 있는 것은 여기서 못 박는다.
이 값들을 바꾸려면 다른 Phase에 미치는 영향을 확인하고 이 문서를 먼저 갱신할 것.

| 항목 | 고정값 | 이유 |
|---|---|---|
| 테스트용 Kafka | **`@EmbeddedKafka`** (Testcontainers 아님) | 이 규모에서 테스트마다 Docker 의존을 붙이는 비용이 과하다. Phase 2/3/5가 모두 동일하게 사용해야 Phase 5가 앞선 테스트를 재작성하지 않는다 |
| 새 패키지 | **`roomescape.payment.event`**: 이벤트 record + Publisher<br>**`roomescape.payment.recovery`**: 리스너 + 핸들러 + 재투입 리스너 + 컨슈머 설정 | 이벤트는 사실이고 복구는 그 사실에 대한 컨슈머 정책이다. 발행하는 쪽(`PaymentService`)은 `event`만 의존하고 **누가 소비하는지 몰라야 한다.** 의존 방향은 `recovery → event`이고, `event`는 `recovery`를 모른다. 기존 `payment.outbox`는 Phase 4 삭제 대상이라 재사용하지 않는다 |
| 배치 단위 | **같은 애플리케이션** (별도 앱·모듈로 분리하지 않음) | 주 실패 모드가 DB 장애라 컨슈머를 다른 인스턴스로 옮겨도 같은 DB 때문에 같이 실패한다. 앱이 죽어도 메시지는 Kafka에 남는다. 별도 앱이 `payment` 테이블을 직접 쓰면 데이터 소유권을 위반한다. 모듈은 컴파일 의존성 도구라 장애 격리와 무관하다. 컨슈머 부하가 DB 커넥션을 두고 웹 요청과 경쟁하게 되면 같은 앱을 api/worker 역할로 나눠 배포하는 것을 검토한다 |
| Producer 클래스명 | **`PaymentPersistFailedPublisher`** (`FailedPaymentRegister` 대체) | 더 이상 "등록(register)"이 아니라 "발행"이다. 이름이 구조를 따라가야 한다 |
| Payload 레코드 | **`PaymentPersistFailed(Long paymentId)`** (신규 record, `payment.event` 패키지) | 기존 `ReservationPaymentEvent`는 엔티티를 안고 있어 수정보다 신규가 깔끔하다. 기존 것은 Phase 4에서 삭제. 이름·필드 근거는 체크리스트 2·6번 |
| 도메인 선행 변경 | **`Payment.getId()`, `Payment.isCompleted()` 추가** | 키·조회(`paymentId`)와 멱등성 체크에 필요한데 현재 둘 다 없다. 상태 getter를 노출하는 대신 `isCompleted()`로 캡슐화를 유지한다 (체크리스트 4·9번) |
| 과도기 공존 | **없음. Phase 2에서 Outbox 저장을 Kafka publish로 즉시 교체** | 기존 복구 경로는 한계 2 때문에 **현재 0% 동작**이다(스케줄러 미실행). 지킬 것이 없으므로 이중 쓰기 과도기를 둘 이유가 없다 |

이 표의 결정을 바꾸고 싶으면 먼저 사용자(프로젝트 주인)와 합의하고, 이 문서를 갱신한 뒤 진행할 것.

### 개발 방식: TDD (필수)

**모든 프로덕션 코드는 실패하는 테스트를 먼저 쓴 뒤에 작성한다.** RED(실패 확인) → GREEN(최소 구현) →
REFACTOR. 컴파일 실패도 RED로 친다. 각 phase 문서에 "TDD 진행 순서"가 있으니 그 순서대로 진행한다.

| 항목 | 고정값 | 이유 |
|---|---|---|
| 테스트 2층 구조 | **단위**(Kafka 없음, mock) → **통합**(`@EmbeddedKafka`) 순서로 쌓는다 | 통합 테스트는 컨텍스트 기동이 수 초~수십 초라 RED-GREEN 사이클이 느리다. 분기 로직(멱등 skip, 미존재 예외 등)은 단위에서 ms 단위로 돌리고, 통합은 **배선**(토픽명·직렬화·에러 핸들러·DLT 라우팅·재투입)만 검증한다 |
| 핸들러 분리 | 처리 로직은 **핸들러 클래스**, `@KafkaListener`는 핸들러를 호출하는 **얇은 어댑터** | 리스너 메서드에 로직을 넣으면 Kafka 없이 단위 테스트할 수 없다. TDD가 강제하는 분리이고, 재투입 리스너가 같은 핸들러를 재사용하는 근거이기도 하다 |
| 통합 테스트 하네스 | **`@KafkaIntegrationTest`** 커스텀 애노테이션 (Phase 1 산출물, `roomescape.util`) | 기존 `@IntegrationTest` 패턴을 따른다. 모든 Kafka 통합 테스트가 **같은 설정**을 쓰면 Spring 테스트 컨텍스트 캐시가 재사용되어 두 번째 클래스부터는 브로커를 다시 띄우지 않는다 |
| 비동기 검증 | **Awaitility** 사용. **`Thread.sleep` 금지** | 고정 대기는 느리거나 간헐적으로 실패한다. 조건이 충족될 때까지 폴링하고 타임아웃으로 끊는다 |
| 로그 검증 | `OutputCaptureExtension`(spring-boot-test)으로 CRITICAL 로그를 검증 | publish 실패·DLT 도달 시 "로그가 남는다"가 완료 기준이라 테스트로 확인해야 한다. 프로젝트에서 처음 쓰는 도구다 |
| 테스트 컨벤션 | `@DisplayName` 한글, AssertJ, `roomescape.fixture.*` 재사용 | 기존 테스트와 동일 |

**phase별 TDD 적용 범위**

| Phase | TDD | 이유 |
|---|---|---|
| 1 | **제외** — 대신 하네스를 만든다 | 인프라 설정이라 먼저 쓸 테스트가 없다. Phase 2·3 TDD의 전제 조건을 만드는 단계 |
| 2 | **적용** | 단위(발행 호출·실패 로그) → 통합(실제 발행·tx3 롤백) |
| 3 | **적용 (본무대)** | 단위(`isCompleted`·핸들러 분기) → 통합(소비·DLT·재시도 불가·재투입) |
| 4 | 무관 | 삭제 작업 |
| 5 | **보강분에만 적용** | Phase 2·3에서 테스트가 이미 나오므로, 빠진 시나리오만 테스트 먼저 작성 |
| 6 | 무관 | 문서화 |

## Phase 목록

| Phase | 파일 | 한 줄 요약 | 핵심 산출물 | 선행 조건 |
|---|---|---|---|---|
| 1 | `phase1_kafka_infra.md` | Kafka를 띄우고 토픽을 선언하고, TDD용 테스트 하네스를 만든다 | docker-compose Kafka + `NewTopic` 빈 2개(원본/DLT) + `@KafkaIntegrationTest` | 없음 |
| 2 | `phase2_producer.md` | 복구 기록을 DB가 아니라 Kafka로 보낸다 | `PaymentPersistFailedPublisher` | Phase 1 |
| 3 | `phase3_consumer_dlq.md` | 메시지를 소비해 결제를 완료 처리하고, 실패는 DLQ로 격리한다 | Consumer + 에러 핸들러 + **DLQ 재투입 경로** | Phase 2 |
| 4 | `phase4_outbox_cleanup.md` | 기존 Outbox 코드를 지운다 (되돌리기 어려움 ⚠️) | Outbox 관련 파일 전부 삭제 | Phase 2, 3 **동작 확인 후** |
| 5 | `phase5_verification.md` | 테스트 커버리지를 점검하고, 빠진 시나리오를 보강하고, 전체 그린을 확인한다 | 시나리오↔테스트 매핑표 + end-to-end 테스트 1개 + 전체 그린 | Phase 1~4 |
| 6 | `phase6_documentation.md` | 왜 이렇게 했는지 기록한다 | `technical_decision/11_*.md` | Phase 1~5 |

### Phase별 상세 요약

**Phase 1 — Kafka 로컬 인프라**
Kafka를 docker-compose로 띄우고(KRaft 모드), `spring-kafka` 의존성과 `bootstrap-servers` 설정을 추가한다.
`payment.persist.failed`와 `payment.persist.failed.DLT`를 `@Bean NewTopic`으로 **명시 선언**한다
(파티션 수를 양쪽 1로 맞추고, DLT에 긴 retention을 건다). Producer/Consumer 로직은 쓰지 않는다 —
"연결이 되는가"만 확인. **TDD 대상이 아닌 대신, Phase 2·3의 TDD를 가능하게 하는 `@KafkaIntegrationTest`
하네스와 Awaitility 의존성을 만든다.**

**Phase 2 — Producer 전환**
tx3 롤백 시 Outbox 테이블에 쓰던 것을 Kafka publish로 교체한다. payload를 엔티티 스냅샷에서
`PaymentPersistFailed(paymentId)` **식별자 하나**로 축소하고(키도 `paymentId`), `@Transactional(REQUIRES_NEW)`를
제거한다(그게 한계 1의 원인이었다). 선행 작업으로 `Payment.getId()`를 추가한다. 🔴 `send()`는 비동기이므로 `whenComplete`로 실패를 받아 CRITICAL
로그를 남겨야 한다 — 안 하면 publish 실패가 무음 유실된다.
TDD: Publisher 단위 테스트(발행 호출 → 실패 로그) → 통합 테스트(실제 발행 → tx3 롤백 시나리오).

**Phase 3 — Consumer + 재시도 + DLQ**
`payment.persist.failed`를 소비해서 `paymentId`로 Payment를 조회하고, `isCompleted()`면 skip(멱등성),
아니면 `complete()` 후 저장한다. `DefaultErrorHandler` 3회 재시도 후 `DeadLetterPublishingRecoverer`로 `.DLT`로
보내되, 역직렬화 실패와 Payment 미존재는 **재시도 없이 바로** 보낸다(`ErrorHandlingDeserializer` + 재시도 불가 예외 등록).
🔴 **이 Phase의 핵심 산출물은 Consumer가 아니라 DLQ 재투입 경로다** — DB 장애 시 전건이 DLQ로
가는 설계이므로, 재투입이 없으면 설계가 작동하지 않는다. 재투입 리스너는 원본 토픽으로 되돌리지 않고
같은 핸들러로 직접 처리한다.
TDD: `PaymentTest`(`isCompleted`) → 핸들러 단위 테스트(skip·완료·미존재) → 통합 테스트(소비·DLT·재시도 불가·재투입).

**Phase 4 — 기존 Outbox 정리** ⚠️ 되돌리기 어려움
`Outbox`/`OutboxRepository`/`OutboxMapper`/`OutboxStatus`/`OutboxProcessor`/`OutboxEventType`,
그리고 Phase 2에서 대체된 `FailedPaymentRegister`/`ReservationPaymentEvent`를 삭제한다.
**삭제 전 프로젝트 주인에게 범위를 말하고 확인받는다.** 기존에 깨져 있던 Outbox 기준 테스트는
**원인을 디버깅하지 않고 삭제**한다(대체 테스트는 Phase 5).

**Phase 5 — 커버리지 점검 + 보강 + 전체 그린**
TDD로 Phase 2·3에서 테스트가 먼저 나오므로, 새로 쓰는 게 아니라 **점검**한다. 완료 기준의 시나리오
(정상 복구 / DLQ 도달 / **DLQ 재투입** / 멱등성 / publish 실패 / 재시도 불가)를 기존 테스트에 매핑하고,
빈칸만 테스트 먼저 작성해 보강한다. Phase 5만의 고유 시나리오는 **진짜 end-to-end** 하나다 —
예약 생성 → tx3 롤백 → 발행 → 소비 → `COMPLETED`. Phase 2는 발행까지, Phase 3은 소비부터라 그 이음새는
여기서만 검증된다. 마지막으로 Kafka 통합 테스트 반복 실행(간헐 실패 점검)과 전체 `./gradlew test` 그린을 확인한다.

**Phase 6 — 의사결정 문서화**
`technical_decision/11_outbox-to-kafka-dlq.md` 작성. `05_orphan-payment-handling.md`의 후속편으로,
실패 모드 특정 → 같은 DB Outbox의 구조적 결함 → 자가 구현 재시도의 비용 → **기각한 대안(복구 전용
별도 DB, 무한 재시도)** → DLQ의 위치 → source of truth 순서로 쓴다. 범위 밖으로 미뤄둔 항목은
다음 트랙 메모로 분리한다.

## 전체 완료 기준

- 결제 복구 플로우가 Outbox 테이블 없이 Kafka + DLQ로 완전히 동작한다.
- `OutboxProcessor`/`Outbox`/`OutboxRepository`/`OutboxMapper` 등 기존 Outbox 코드가 삭제됐다.
- Producer의 publish 실패가 **CRITICAL 로그로 실제 관측된다**(무음 유실이 없다).
- 장애 주입 통합테스트(정상 재시도 성공 케이스 + DLQ 도달 케이스 + 멱등성 케이스)가 모두 통과한다.
- DLQ 재투입 방법이 문서화돼 있고, **재투입이 실제로 동작하는 것이 테스트로 확인됐다**.
- `.DLT` retention이 명시적으로 길게 설정돼 있다 ((ㄱ) 설계에서 유실 방지선).
- Phase 5의 시나리오↔테스트 매핑표에 빈칸이 없고, Kafka 통합 테스트가 반복 실행에서도 간헐 실패 없이 통과한다.
- technical_decision 문서로 전환 근거가 기록됐다 — **기각한 대안(복구 전용 별도 DB) 포함.**

## 이번 범위에 포함하지 않는 것

- **`payment.status=IN_PROGRESS` 정산 스윕 잡 구현** — 설계상 2차 방어선으로 필요하다는 것은
  Phase 6에 문서화하되, 구현은 다음 트랙. (이번 범위는 Kafka 전환 검증이므로 섞지 않는다)
- 대기 승격 알림 등 결제 외 다른 도메인 이벤트의 Kafka 전환 (별도 트랙)
- DLQ 자동 재처리 루프 (수동 재투입 절차 문서화까지만)
- 운영 알림 연동(Slack 등) — CRITICAL 로그까지만. 무엇을 볼지(지표)는 Phase 0에 정의돼 있음
- 보안(SASL/TLS/ACL) — 로컬 범위. 운영 시 필요한 것은 체크리스트 22번
- Kafka Streams/KSQL 등 스트림 처리
- 운영 환경(실제 배포) Kafka 클러스터 구성 — 로컬(docker-compose) 범위만
- 기존에 깨져 있던 테스트의 **원인 디버깅** (Phase 4에서 삭제, 원인은 다음 트랙 메모로)