# Phase 4: 기존 Outbox 코드/테이블 정리

**시작 전에 `00_overview.md`를 먼저 읽을 것.**

⚠️ **이 Phase는 되돌리기 어려운 작업(기존 코드 삭제, DB 테이블 drop)을 포함한다.**
삭제/drop을 실행하기 전에 반드시 프로젝트 주인에게 "지금부터 Outbox 테이블과 관련 코드를
삭제하겠다"고 알리고 확인받을 것. 한 번 승인받았다고 이후 비슷한 삭제를 또 묻지 않고
진행하지 말 것 — 이 Phase 안에서도 삭제 대상이 여러 개면 범위를 명확히 말하고 진행한다.

## 선행 조건

**Phase 2, 3이 모두 완료되고, Kafka 경로(정상 처리 + DLQ)가 실제로 동작 확인된 뒤에 시작한다.**
Kafka 경로가 검증되기 전에 기존 Outbox 복구 경로를 지우면, 그 사이에 결제 복구가 필요한 장애가
발생했을 때 복구할 방법이 아예 없어진다. 순서를 반드시 지킬 것.

## 목표

Kafka + DLQ로 완전히 대체됐으므로, 기존 Outbox 기반 복구 메커니즘을 제거한다.

## 삭제 대상

- `src/main/java/roomescape/common/outbox/OutboxProcessor.java` (스케줄러)
- `src/main/java/roomescape/common/outbox/Outbox.java` (엔티티)
- `src/main/java/roomescape/common/outbox/OutboxRepository.java`
- `src/main/java/roomescape/common/outbox/OutboxMapper.java`
- `src/main/java/roomescape/common/outbox/OutboxStatus.java`
- `src/main/java/roomescape/common/OutboxEventType.java` (실질적으로 거의 미사용이던 enum)
- `src/main/java/roomescape/payment/outbox/FailedPaymentRegister.java` —
  Phase 2에서 `PaymentPersistFailedPublisher`로 대체되어 참조가 끊긴 상태. 파일 삭제.
- `src/main/java/roomescape/payment/outbox/ReservationPaymentEvent.java` —
  `PaymentPersistFailed`로 대체됨. 파일 삭제. (`payment.outbox` 패키지 자체가 비워진다)
- DB: `outbox_event` 테이블. 현재 `ddl-auto: create-drop` + H2 in-memory이므로 **엔티티 삭제만으로
  테이블도 사라진다** — 별도 drop 스크립트가 필요한지 먼저 확인할 것.
  `src/main/resources/data.sql`에 참조가 없는지도 확인 (현재는 없는 것으로 보임).

## 할 일

1. 위 삭제 대상 파일들을 참조하는 곳이 더 없는지 grep으로 확인 (`grep -rln "Outbox" src/main src/test`).
2. 삭제 전 사용자 확인 받기 (위 경고 참고).
3. 파일 삭제, DB 테이블 정리.
4. 전체 컴파일 확인 (`./gradlew compileJava compileTestJava`).
5. 기존 Outbox 기준 테스트 **삭제**:
   - `src/test/java/roomescape/reservation/ReservationIntegrationTest.java`의
     `createReservation_PaymentSuccess_DBFail_OutboxCreated`.
   - 이 테스트는 **이 작업 시작 전부터 이미 실패하던 기존 결함**이다(이번 전환이 만든 회귀가 아님).
   - 🔴 **원인을 디버깅하지 말 것.** `outboxRepository.count()` assertion은 삭제될 코드에 의존하고 있어
     재작성 대상이 아니라 삭제 대상이다. 원인 미상의 레거시 mock 문제를 마이그레이션 도중에 파헤치면
     범위가 무한히 늘어난다.
   - 대체 테스트(Kafka 기준 end-to-end)는 **Phase 5에서 새로 작성한다.** 여기서 쓰지 말 것 — 중복이다.
   - 삭제 시 "원인 미상의 기존 실패 테스트를 삭제했다"는 사실을 메모로 남겨 Phase 6에 전달한다.

## 완료 기준

- `Outbox` 관련 코드/테이블이 저장소에서 완전히 사라졌다 (`grep -rn "Outbox" src/` 결과 없음).
- `./gradlew compileJava compileTestJava`가 성공한다.
- `./gradlew test`가 이번 전환과 무관한 기존 실패 외에 새로운 실패 없이 돌아간다.
- 삭제한 레거시 테스트와 그 사유가 메모로 남아 Phase 6에 전달된다.

## 이번 Phase에서 하지 않는 것

- Kafka/Consumer 로직 자체의 추가 변경 (Phase 2, 3에서 이미 끝난 것으로 간주)
- **새 통합테스트 작성 → Phase 5** (여기서는 삭제만)
- 삭제한 레거시 테스트의 실패 원인 규명 → 다음 트랙 (메모로만 남긴다)
