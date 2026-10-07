# Phase 1: Kafka 로컬 인프라 구성

**시작 전에 `00_overview.md`를 먼저 읽을 것.** 전체 배경과 Phase 0 설계 결정이 거기 있다.

## 선행 조건

없음. 가장 먼저 진행하는 Phase.

## 목표

로컬 docker-compose 환경에 Kafka를 띄우고, Spring Boot 애플리케이션이 Kafka와 통신할 수 있는
기본 설정을 갖춘다. **이 Phase에서는 실제 Producer/Consumer 로직을 구현하지 않는다** — 그건
Phase 2, 3의 몫이다. 여기서는 "연결이 되는가"만 확인한다.

## 할 일

1. `docker-compose.yml`에 Kafka 서비스 추가.
   - KRaft 모드(Zookeeper 불필요) 사용을 우선 검토 — 최신 Kafka 이미지는 Zookeeper 없이도
     단일 노드로 띄울 수 있어 로컬 개발 환경이 단순해진다. 기존 `docker-compose.perf.yml`의
     k6 서비스 패턴(주석으로 용도 설명)을 참고해서 일관된 스타일로 작성.
   - 로컬 전용이므로 복제 팩터(replication factor) 1, **파티션 수 1**로 통일한다
     (00_overview.md Phase 0 표 — DLT 파티션 수를 원본과 맞춰야 하므로 양쪽 다 1).
2. `build.gradle`에 `spring-kafka` 의존성 추가. 테스트용으로 `spring-kafka-test`도 함께
   (00_overview.md 고정값: 테스트는 `@EmbeddedKafka` 사용, Testcontainers 아님).
3. `application.yml`(또는 프로필별 yml)에 `spring.kafka.bootstrap-servers` 등 기본 설정 추가.
   - 기존 `application-perf*.yml` 프로필 구조를 참고해서, 로컬 개발/테스트 환경에 맞는 설정으로.
4. **토픽을 `@Bean NewTopic`으로 명시적으로 선언**한다 — `payment.persist.failed`와
   `payment.persist.failed.DLT` 둘 다. 각각 partitions=1, replicas=1.
   - **DLT에는 retention 30일**(`retention.ms`)을 설정한다. 원본은 기본값 7일 (체크리스트 17번).
   - 로컬 브로커는 **auto-create를 끈다**(브로커 설정 `auto.create.topics.enable=false`. 환경변수 이름은
     쓰는 이미지에 따라 다르니 확인할 것). 토픽명 오타 시 조용히 새 토픽이 생기지 않게 하려는 것이다 (20번).
   - auto-create에 의존하면 DLT가 기본 파티션 수로 생성돼 원본과 어긋날 수 있다.
     `DeadLetterPublishingRecoverer`는 기본적으로 **원본과 같은 파티션 번호로** 전송하므로,
     DLT 파티션 수가 더 적으면 전송이 실패한다 (00_overview.md Phase 0 표).
   - 토픽 선언만 하고, Producer/Consumer 로직은 Phase 2, 3의 몫이다.
5. 연결 확인:
   - `docker-compose up`으로 Kafka 컨테이너가 정상 기동하는지 확인.
   - 컨테이너 안에서 `kafka-topics --describe`로 토픽 2개의 파티션 수와 DLT retention을 확인.
6. **TDD 테스트 하네스를 만든다.** 이 Phase는 TDD 대상이 아니지만(인프라 설정이라 먼저 쓸 테스트가 없다),
   Phase 2·3의 TDD는 이 하네스가 있어야 시작할 수 있다. (00_overview.md "개발 방식: TDD")
   - `build.gradle`에 `testImplementation 'org.awaitility:awaitility'` 추가. 버전은 Spring Boot BOM이 관리하는
     것으로 알고 있지만 **확인 필요** — 버전 없이 빌드가 안 되면 명시한다.
   - `roomescape.util.KafkaIntegrationTest` 커스텀 애노테이션 작성. 기존 `@IntegrationTest`를 메타 애노테이션으로
     포함하고 `@EmbeddedKafka`를 더한다:
     - `partitions = 1`, 토픽 `payment.persist.failed`·`payment.persist.failed.DLT`
     - 임베디드 브로커 주소를 `spring.kafka.bootstrap-servers`에 주입 (`@EmbeddedKafka`의
       `bootstrapServersProperty` 속성 — 정확한 속성명은 사용하는 spring-kafka-test 버전에서 확인)
   - test 프로필에 `spring.kafka.consumer.auto-offset-reset=earliest` — `latest`면 컨슈머가 구독을 마치기 전에
     보낸 메시지를 놓쳐 테스트가 간헐적으로 실패한다 (체크리스트 15번).
   - **스모크 테스트 1개**: `@KafkaIntegrationTest`로 컨텍스트가 뜨고, `KafkaTemplate`으로 보낸 메시지를
     Awaitility로 받아 확인한다. 이 테스트는 **지우지 않고 남긴다** — 하네스가 동작한다는 증거다.

## 완료 기준

- `docker-compose up`으로 Kafka가 기동된다.
- Spring Boot 애플리케이션 컨텍스트가 Kafka 설정을 포함해서 정상 기동된다 (`./gradlew bootRun` 또는
  관련 통합테스트로 확인).
- `payment.persist.failed`와 `payment.persist.failed.DLT`가 **파티션 수 1로 동일하게**
  생성된 것을 `kafka-topics --describe`로 확인했다.
- DLT retention이 30일로 설정돼 있고, 로컬 브로커의 auto-create가 꺼져 있다.
- `@KafkaIntegrationTest` 스모크 테스트가 통과한다 (메시지 왕복 확인, `Thread.sleep` 없이 Awaitility로).
- 스모크 테스트를 **두 번 연속 실행해도** 통과한다 (간헐 실패 없음).

## 이번 Phase에서 하지 않는 것

- 실제 Producer 구현 → Phase 2 (여기서는 토픽 선언까지만)
- Consumer, `DefaultErrorHandler`/`DeadLetterPublishingRecoverer` 설정 → Phase 3
