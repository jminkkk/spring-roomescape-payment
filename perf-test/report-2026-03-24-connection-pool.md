# 커넥션 풀 최적화 설정 유의미성 검증

## 테스트 개요

- **날짜**: 2026-03-24
- **대상 엔드포인트**: POST /reservations → 내부적으로 `https://httpbin.org/delay/0.2` 아웃바운드 호출
- **테스트 툴**: k6 (grafana/k6:0.52.0)
- **외부 API**: httpbin.org `/delay/0.2`
  - 실제 TLS 핸드셰이크 발생 → LIFO/FIFO 비교 유효
  - 200ms 고정 딜레이 → 외부 API 응답 시간 고정
- **핵심 전제**: 비교 대상 없이는 "좋다"는 근거가 없다

---

## 비교 기준 3단계

| 단계 | 프로파일 | 특성 |
|------|---------|------|
| **Baseline 1** | `perf,perf-no-pool` | `SimpleClientHttpRequestFactory` — OS keep-alive로 커넥션 재사용 가능, 명시적 풀 관리 없음 |
| **Baseline 2** | `perf,perf-default-pool` | Apache HttpClient + 풀링 있음, 최적화 옵션 전부 OFF (FIFO, LENIENT, TTL 없음, AIMD 없음) |
| **Current** | `perf` | Apache HttpClient + 풀링 + 최적화 (LIFO, STRICT, TTL=60s, AIMD) |

> Baseline 1과 Baseline 2를 모두 잡는 이유:
> "풀링 자체의 효과"와 "최적화 설정의 효과"를 분리해서 측정하기 위함.
> Baseline 1 → Baseline 2: 풀링 도입 효과 / Baseline 2 → Current: 최적화 설정 효과

---

## 검증 로드맵

| Scenario | 파일 | 비교 대상 | 핵심 질문 |
|----------|------|----------|----------|
| 00 | `00_smoke-verify.js` | 앱 로그 확인 | 세 프로파일에서 풀링이 예상대로 동작하는가? |
| 01 | `01_baseline-vs-pool.js` | Baseline 1 / Baseline 2 / Current | 풀링 + 최적화 각각의 기여는? |
| 02 | `02_lifo-vs-fifo.js` | LIFO vs FIFO | LIFO가 TLS handshake를 줄이는가? |
| 03 | `03_strict-vs-lenient.js` | STRICT vs LENIENT | p99가 실제로 안정적인가? |
| 04 | `04_pool-size-ramp.js` | VU 10→60 ramp | maxConnPerRoute=50이 병목인가? |

---

## Scenario 00: 풀링 동작 여부 사전 검증

세 프로파일이 각각 예상한 대로 동작하는지 확인한다.
- `perf-no-pool`: 풀 로그 없어야 함 (SimpleClientHttpRequestFactory)
- `perf-default-pool`: 풀 로그 있음, FIFO+LENIENT+TTL없음 확인
- `perf`: 풀 로그 있음, LIFO+STRICT+TTL=60s 확인

### 검증 방법: 앱 로그 기반 (k6 timings으로는 불가)

k6의 `http_req_connecting`은 **k6→앱** 구간의 TCP 연결 시간이다. 우리가 확인해야 하는 **앱 → httpbin.org** 아웃바운드 커넥션 재사용 여부는 k6 metrics에서 직접 보이지 않는다. 반드시 앱 로그로 확인해야 한다.

### 실행 (로그를 파일로 저장 후 확인)

```bash
# [검증 A] Baseline 1: no-pool
./gradlew bootRun --args='--spring.profiles.active=perf,perf-no-pool' 2>&1 | tee /tmp/app-no-pool.log
# → 별도 터미널: K6_SCRIPT=00_smoke-verify.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "endpoint leased" /tmp/app-no-pool.log   # → 0 이어야 함
https://www.notion.so/image/attachment%3Ab81b0457-209c-49a6-a867-74a3768d2dc9%3Aimage.png?table=block&id=32d3484d-8067-80ee-b5c1-d020ffd97417&spaceId=1430a828-8bfa-4acb-bfb9-0585b0e739c2&width=2000&userId=5858d2c9-4416-458c-b038-4edcab33cd56&cache=v2

# [검증 B] Baseline 2: default-pool (최적화 옵션 OFF)
./gradlew bootRun --args='--spring.profiles.active=perf,perf-default-pool' 2>&1 | tee /tmp/app-default-pool.log
# → 별도 터미널: K6_SCRIPT=00_smoke-verify.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "endpoint leased" /tmp/app-default-pool.log                      # → 5 이어야 함
grep "executing exchange" /tmp/app-default-pool.log | grep -oE "http-outgoing-[0-9]+" | sort | uniq -c
# → FIFO: 여러 VU 있을 때 다양한 ID 분산 (smoke=1 VU라면 동일 ID 반복 가능)

https://www.notion.so/image/attachment%3A6aba9267-d6b0-4dbb-a324-ff3d7a9603da%3Aimage.png?table=block&id=32d3484d-8067-8025-b3d8-c66d3fda3fe9&spaceId=1430a828-8bfa-4acb-bfb9-0585b0e739c2&width=2000&userId=5858d2c9-4416-458c-b038-4edcab33cd56&cache=v2

https://www.notion.so/image/attachment%3A893c5107-7b75-4882-8003-15a374dd5746%3Aimage.png?table=block&id=32d3484d-8067-805b-b4a8-d87f330a9e82&spaceId=1430a828-8bfa-4acb-bfb9-0585b0e739c2&width=2000&userId=5858d2c9-4416-458c-b038-4edcab33cd56&cache=v2

# [검증 C] Current: 최적화 설정
./gradlew bootRun --args='--spring.profiles.active=perf' 2>&1 | tee /tmp/app-pool.log
# → 별도 터미널: K6_SCRIPT=00_smoke-verify.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "endpoint leased" /tmp/app-pool.log                              # → 5 이어야 함
grep "executing exchange" /tmp/app-pool.log | grep -oE "http-outgoing-[0-9]+" | sort | uniq -c
# → LIFO: 특정 ID에 집중 (최근 커넥션 재사용)
```

### 예상 결과

| 프로파일 | pool leased 로그 수 | 커넥션 ID 패턴 | TLS ClientHello 수 |
|---------|------------------|--------------|------------------|
| no-pool | 0 | 없음 | = 요청 수 (5) |
| default-pool | 5 | 매번 다른 ID 가능 (FIFO) | 재사용 시 1, 아니면 > 1 |
| current (LIFO) | 5 | 같은 ID 반복 | 1 (첫 요청에만) |

### TLS Handshake 직접 확인 (선택)

```bash
# -Djavax.net.debug=ssl:handshake는 Gradle 데몬이 아닌 Spring Boot 프로세스 JVM에 넣어야 함
# --args 가 아니라 JAVA_OPTS 환경변수로 전달해야 forked JVM에 적용됨

# no-pool
JAVA_OPTS="-Djavax.net.debug=ssl:handshake" ./gradlew bootRun \
  --args='--spring.profiles.active=perf,perf-no-pool' 2>&1 | tee /tmp/app-00-no-pool-ssl.log
# → 별도 터미널: K6_SCRIPT=00_smoke-verify.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "ClientHello" /tmp/app-00-no-pool-ssl.log   # → 5 (매 요청마다 handshake)

# current (LIFO pool)
JAVA_OPTS="-Djavax.net.debug=ssl:handshake" ./gradlew bootRun \
  --args='--spring.profiles.active=perf' 2>&1 | tee /tmp/app-00-pool-ssl.log
# → 별도 터미널: K6_SCRIPT=00_smoke-verify.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "ClientHello" /tmp/app-00-pool-ssl.log      # → 1 (첫 요청 때만, 이후 TLS session 재사용)
```

### 검증 결과

| 확인 항목 | no-pool 실제 | pool 실제 | 판단 |
|----------|------------|---------|------|
| "endpoint leased" 로그 수 | **0** | **5** | ✅ no-pool=0, pool=5 |
| 커넥션 ID 패턴 | 없음 | **http-outgoing-0 × 5** | ✅ 동일 ID 반복 → 커넥션 재사용 확인 |
| TLS handshake 수 | 미측정 (SSL 디버그 미적용) | **1** (첫 요청만) | ✅ 이후 4번은 TLS session 재사용 |

> ClientHello 비교(no-pool vs pool)는 JAVA_OPTS SSL 디버그 미적용으로 no-pool 측정 안 됨.

---

## 사전 준비

### 앱 실행 (공통)
```bash
# Docker Desktop 실행 확인
# WireMock 불필요 - httpbin.org 직접 사용

# 공통 perf 프로파일 + 시나리오별 추가 프로파일 지정
./gradlew bootRun --args='--spring.profiles.active=perf[,추가프로파일]'

# 헬스 확인
curl http://localhost:8080/api/actuator/health
```

### httpbin.org 연결 확인
```bash
curl -w "\nTotal: %{time_total}s\n" https://httpbin.org/delay/0.2
# → ~200ms 후 응답 오면 OK
```

---

## Scenario 01: 3단계 비교 (no-pool / default-pool / current)

### 목적
풀링 도입 효과와 최적화 설정 효과를 분리해서 측정한다.
- Baseline 1 → Baseline 2: "풀링 자체가 latency를 얼마나 줄이는가?"
- Baseline 2 → Current: "LIFO+STRICT+TTL+AIMD 설정이 추가로 얼마나 기여하는가?"

### 실행 (앱 3회 재시작 필요 — 동일 스크립트, 프로파일만 교체)
```bash
# [Step 1] Baseline 1: no-pool
./gradlew bootRun --args='--spring.profiles.active=perf,perf-no-pool' 2>&1 | tee /tmp/app-01-no-pool.log
# → 별도 터미널: K6_SCRIPT=01_baseline-vs-pool.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "endpoint leased" /tmp/app-01-no-pool.log   # → 0 이어야 함

# [Step 2] Baseline 2: default-pool
./gradlew bootRun --args='--spring.profiles.active=perf,perf-default-pool' 2>&1 | tee /tmp/app-01-default-pool.log
# → 별도 터미널: K6_SCRIPT=01_baseline-vs-pool.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "endpoint leased" /tmp/app-01-default-pool.log  # → 요청 수만큼
grep "executing exchange" /tmp/app-01-default-pool.log | grep -oE "http-outgoing-[0-9]+" | sort | uniq -c
# → FIFO: 여러 ID 분산 (오래된 커넥션 선택)

# [Step 3] Current: 풀링 + 최적화
./gradlew bootRun --args='--spring.profiles.active=perf' 2>&1 | tee /tmp/app-01-current.log
# → 별도 터미널: K6_SCRIPT=01_baseline-vs-pool.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "endpoint leased" /tmp/app-01-current.log       # → 요청 수만큼
grep "executing exchange" /tmp/app-01-current.log | grep -oE "http-outgoing-[0-9]+" | sort | uniq -c
# → LIFO: 특정 ID에 집중 (최근 커넥션 재사용)
```

### TLS Handshake 카운트 (앱 로그)
```bash
# JAVA_OPTS로 forked JVM에 전달해야 함
JAVA_OPTS="-Djavax.net.debug=ssl:handshake" ./gradlew bootRun \
  --args='--spring.profiles.active=perf,perf-no-pool' \
  2>&1 | grep -c "ClientHello"
# no-pool: 요청 수만큼 / default-pool: 첫 연결 시만 / current: 첫 연결 시만
```

### 검증 기준
> 예상 값은 httpbin.org 200ms delay + TLS overhead 기반 rough estimate. 실제 측정 후 업데이트.

| 메트릭 | Baseline 1 (no-pool) | Baseline 2 (default-pool) | Current | B1 실제 | B2 실제 | C 실제 |
|--------|---------------------|--------------------------|---------|---------|---------|--------|
| p50 latency | ~400ms | ~300ms | ~250ms | - | - | - |
| p95 latency | ~800ms | ~600ms | ~500ms | - | - | - |
| TLS ClientHello 수 | = 요청 수 | < 요청 수 | < 요청 수 | - | - | - |

> Baseline 1 → 2 개선이 크다면: 풀링 자체의 효과가 지배적
> Baseline 2 → Current 개선이 크다면: LIFO/STRICT/TTL/AIMD 최적화 효과가 유의미

---

## Scenario 02: LIFO vs FIFO

### 목적
LIFO가 "최근 커넥션 재사용 → TLS session 재활용"으로 FIFO보다 full handshake를 줄이는지 수치로 확인한다.

### 실험 설계 근거
VU=15, sleep=0.5s: 15개 커넥션이 모두 풀에 반납되고 idle 상태가 된 후 다음 요청이 들어오는 상황을 만든다.
- **LIFO**: 가장 최근 반납된 (= TLS session이 살아있을 확률이 높은) 커넥션을 재사용
- **FIFO**: 가장 오래된 (= TLS session이 만료됐을 확률이 높은) 커넥션 선택

### 실행
```bash
# LIFO (기본 perf 프로파일)
JAVA_OPTS="-Djavax.net.debug=ssl:handshake" ./gradlew bootRun \
  --args='--spring.profiles.active=perf' 2>&1 | tee /tmp/app-02-lifo.log
# → 별도 터미널: K6_SCRIPT=02_lifo-vs-fifo.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "ClientHello" /tmp/app-02-lifo.log   # → 첫 커넥션 수립 시만 발생 예상
grep "executing exchange" /tmp/app-02-lifo.log | grep -oE "http-outgoing-[0-9]+" | sort | uniq -c
# → 소수의 ID에 집중 (LIFO: 최근 커넥션 재사용)

# FIFO (비교군)
JAVA_OPTS="-Djavax.net.debug=ssl:handshake" ./gradlew bootRun \
  --args='--spring.profiles.active=perf,perf-fifo' 2>&1 | tee /tmp/app-02-fifo.log
# → 별도 터미널: K6_SCRIPT=02_lifo-vs-fifo.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "ClientHello" /tmp/app-02-fifo.log   # → LIFO 대비 더 많이 발생 예상
grep "executing exchange" /tmp/app-02-fifo.log | grep -oE "http-outgoing-[0-9]+" | sort | uniq -c
# → ID 다양하게 분산 (FIFO: 오래된 커넥션 선택)
```

### 검증 기준
| 메트릭 | LIFO 예상 | FIFO 예상 | LIFO 실제 | FIFO 실제 |
|--------|---------|---------|---------|---------|
| p50 latency | ~250ms | ~300ms | **455ms** | **452ms** |
| p95 latency | ~400ms | ~600ms | **912ms** | **895ms** |
| TLS handshake 수 | 적음 | 많음 | **10** | **10** |
| unique 커넥션 수 | - | - | **10** | **10** |
| error rate | 0% | 0% | **0%** | **0.22%** |

**LIFO 선택 근거 성립 조건**: FIFO 대비 TLS handshake 수 감소 또는 p50 latency 감소

**실측 결과**: ⚠️ **차이 없음** — httpbin.org keep-alive=3분 > 테스트 60초이므로 TLS session이 만료되지 않아 LIFO/FIFO 차이가 드러나지 않음. Toss 실서버 keep-alive timeout 확인 후 재검증 필요.

### 주의
**TTL=60s 설정과의 관계**: LIFO가 최근 커넥션만 재사용하면, 오래된 커넥션이 방치되다가 TTL에 걸려 교체된다. TTL이 없으면 LIFO의 장점이 "일부 커넥션만 재사용, 나머지는 좀비"가 된다. LIFO와 TTL은 세트로 검증해야 한다.

---

## Scenario 03: STRICT vs LENIENT

### 목적
STRICT가 "예측 가능한 Latency"를 보장한다는 주장을 p99 분산으로 검증한다.

### 실험 설계 근거
VU=40, maxConnPerRoute=50: 풀 크기에 근접하게 설정하여 커넥션 경합을 발생시킨다. 경합이 없으면 두 정책의 차이가 드러나지 않는다.

### 실행
```bash
# STRICT (기본 perf 프로파일)
./gradlew bootRun --args='--spring.profiles.active=perf' 2>&1 | tee /tmp/app-03-strict.log
# → 별도 터미널: K6_SCRIPT=03_strict-vs-lenient.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "endpoint leased" /tmp/app-03-strict.log
grep -c "connectionRequestTimeout\|Timeout waiting" /tmp/app-03-strict.log   # → 경합이 있더라도 0 또는 극소 예상

# LENIENT (비교군)
./gradlew bootRun --args='--spring.profiles.active=perf,perf-lenient' 2>&1 | tee /tmp/app-03-lenient.log
# → 별도 터미널: K6_SCRIPT=03_strict-vs-lenient.js docker compose -f docker-compose.perf.yml run --rm k6
grep -c "endpoint leased" /tmp/app-03-lenient.log
grep -c "connectionRequestTimeout\|Timeout waiting" /tmp/app-03-lenient.log  # → starvation 발생 시 > 0
```

### 검증 기준
| 메트릭 | STRICT 예상 | LAX 예상 | STRICT 실제 | LAX 실제 |
|--------|-----------|--------|-----------|--------|
| p50 latency | 다소 높을 수 있음 | 낮을 수 있음 | **1.99s** | **2.09s** |
| p95 latency | 안정적(낮음) | 불안정(높음) | **2.54s** | **2.60s** |
| max latency | 낮음 | 높음(starvation) | **3.59s** | **4.46s** |
| throughput | - | - | **19.0 req/s** | **18.2 req/s** |
| error rate | 0% | 0% | **0%** | **0%** |

**STRICT 선택 근거 성립 조건**: LAX 대비 p95 낮음 또는 max latency 낮음

**실측 결과**: ✅ **STRICT 유의미** — max latency 870ms 차이, p95 60ms 차이, throughput +0.8 req/s. H2 DB 병목 상황에서도 STRICT의 fair queuing 효과 확인.

**트레이드오프 인식**: p50이 LENIENT < STRICT이더라도, 결제 서버에서 "한 번의 타임아웃"이 "평균 50ms 절약"보다 비용이 크다면 STRICT가 맞다.

---

## Scenario 04: maxConnPerRoute=50 적정성

### 목적
VU를 단계적으로 늘려 maxConnPerRoute=50이 실제 병목이 되는 지점을 찾는다.

### 실행
```bash
./gradlew bootRun --args='--spring.profiles.active=perf' 2>&1 | tee /tmp/app-04-ramp.log
# → 별도 터미널: K6_SCRIPT=04_pool-size-ramp.js docker compose -f docker-compose.perf.yml run --rm k6
```

### 사후 로그 분석
```bash
# 풀 고갈 타임아웃 발생 여부
grep -c "Timeout waiting for connection\|Connection request timed out" /tmp/app-04-ramp.log
# → 0이면 VU=60도 풀 여유 있음 / > 0이면 maxConnPerRoute 상향 검토

# VU 단계별 타임아웃 발생 시점 확인 (타임스탬프로 구간 유추)
grep "Timeout waiting for connection\|Connection request timed out" /tmp/app-04-ramp.log | head -5
```

### 검증 기준
| VU 단계 | 예상 p95 | 예상 error | 실제 p95 (전체 집계) | 실제 error |
|---------|---------|-----------|-------------------|-----------|
| 10 | < 400ms | < 1% | - (단계별 미분리) | 0% |
| 30 | < 600ms | < 1% | - (단계별 미분리) | 0% |
| 50 | < 1000ms | < 3% | - (단계별 미분리) | 0% |
| 60 | 급등 가능 | > 5% ? | 전체 p95=3.29s, max=4.95s | **0%** |

> 단계별 p95 분리가 필요하면 k6 output 옵션(`--out json`)으로 재측정 필요.

**판단**: VU=60, error rate=0%, pool timeout 에러 0 → **maxConnPerRoute=50 현재 값 적정**

단, 전체 p95=3.29s는 H2 DB 직렬화 오버헤드가 포함된 수치. DB 병목 제거 시 실제 pool 여유 더 클 것으로 예상.

---

## TTL=1분 / ValidateAfterInactivity=1분 검증

### 핵심 질문
"왜 1분인가?" — 토스 서버의 실제 keep-alive timeout을 먼저 확인해야 한다.

### 토스 서버 keep-alive 확인 방법
```bash
curl -v https://api.tosspayments.com/v1/payments/confirm \
  -H 'Authorization: Basic ...' 2>&1 | grep -i "keep-alive\|timeout\|connection"
```
→ `Keep-Alive: timeout=N` 헤더가 있다면, TTL은 N보다 짧게 설정해야 한다. (서버가 먼저 끊기 전에 클라이언트가 먼저 교체)

### TTL과 ValidateAfterInactivity를 동시에 60초로 설정한 경우의 문제
- idle 1분 후 유효성 검사가 실행되지만, 바로 그 시점에 TTL도 만료되어 교체된다
- 실질적으로 ValidateAfterInactivity가 TTL보다 먼저 동작하려면 `validateAfterInactivitySeconds < ttlSeconds` 이어야 한다
- 예시: TTL=60s, ValidateAfterInactivity=30s → 30s idle 후 유효성 검사 → 문제없으면 TTL까지 계속 사용

### 현재 설정의 의미
두 값이 동일(60s)한 경우: idle 1분이 되면 유효성 검사와 TTL 교체가 동시에 트리거 → 사실상 ValidateAfterInactivity가 의미 없어질 수 있다.

---

## AIMDBackoffManager 검증

AIMD는 k6 스크립트로 직접 검증하기 어렵다 (httpbin.org 딜레이를 런타임에 바꿀 수 없기 때문). 대신 앱 로그 기반으로 확인한다.

### 확인 방법
```bash
# httpbin.org /delay/2 로 교체 (application-perf.yml 수정 후 앱 재시작)
# payment.confirmPath: /delay/2

./gradlew bootRun --args='--spring.profiles.active=perf' \
  2>&1 | grep -E "Backing off|AIMD|route.*httpbin|pool.*reduced"
```

### 검증 기준
- 지연 증가 후: "Backing off route" 로그 발생 → pool 크기 50% 축소
- 정상화 후: latency 회복 + pool 점진적 복구 (+1씩)

---

## 전체 결과 요약

> **환경 한계 주의**: DB가 H2 in-memory로 동시 트랜잭션 직렬화 오버헤드 발생.
> 커넥션 풀 효과를 순수하게 측정하려면 MySQL(Docker)로 교체 필요.
> 아래 결과는 H2 오버헤드가 포함된 수치임을 감안할 것.

### 비교 기준별 효과 (Scenario 01)

| 비교 구간 | p50 | p95 | TLS handshake | unique 커넥션 수 | 판단 |
|----------|-----|-----|--------------|----------------|------|
| Baseline 1 (no-pool) | 측정 불가* | 측정 불가* | 미측정 | - | Apache HC 로그 없음 (HttpURLConnection) |
| Baseline 2 (default-pool, FIFO/LAX) | 미측정* | 미측정* | 10 | 10 (균등 분산, 99~124회) | 풀링 정상 동작. FIFO의 균등 분산 특성 확인 |
| Current (LIFO/STRICT) | 미측정* | 미측정* | 15 | 15 (0~9 집중, 10~14 급감) | LIFO 특성으로 ramp-up 시 커넥션 더 많이 생성됨 |

> *Scenario 01 k6 수치는 별도 저장 없이 터미널 출력만 확인됨. 재측정 시 `--out json` 옵션 사용 권장.
> **no-pool은 SimpleClientHttpRequestFactory 사용으로 Apache HC 로그 없음. TLS 비교는 JVM SSL 디버그로 별도 측정 필요.**

### 설정별 유의미성

| 설정 | 시나리오 | 유의미 여부 | 실측 근거 | 비고 |
|------|---------|----------|---------|------|
| LIFO (vs FIFO) | 02 | ⚠️ 환경 제약으로 검증 불가 | TLS handshake 동일(10 vs 10), p50 동일(455ms vs 452ms) | httpbin.org keep-alive=3분 > 테스트 60초 → TLS session 만료 없어 차이 안 드러남 |
| STRICT (vs LAX) | 03 | ✅ 유의미 | max latency 3.59s vs 4.46s (+870ms), p95 2.54s vs 2.60s, throughput 1197 vs 1139 | H2 병목 상황에서도 STRICT의 fair queuing 효과 확인 |
| maxConnPerRoute=50 | 04 | ✅ 현재 값 적정 | pool timeout 에러 0, unique 커넥션 30개, VU=60에서 error rate 0%, p95=3.29s, max=4.95s | VU=60 > maxConnPerRoute=50이지만 커넥션 점유 시간이 짧아 큐잉만 발생, timeout 없음 |
| TTL=60s, ValidateAfterInactivity=30s | 이론 | ✅ 수정 완료 | 두 값 동일(60s)이면 ValidateAfterInactivity 사실상 무효 → 30s로 분리 적용 | idle 30s 후 유효성 검사 → 문제없으면 TTL(60s)까지 재사용 |
| AIMDBackoffManager | 미측정 | - | httpbin.org delay 런타임 변경 불가로 미검증 | /delay/2 로 수동 교체 후 "Backing off route" 로그로 확인 가능 |

### 종합 판단

**유효한 설정:**
- **STRICT**: 검증됨. max latency 안정성 확인.
- **maxConnPerRoute=50**: 현재 부하에서 여유 있음.

**검증 환경 제약으로 결론 보류:**
- **LIFO**: 장점이 드러나는 조건(짧은 keep-alive, 불균일 트래픽)에서 재검증 필요.
- **AIMDBackoffManager**: 별도 검증 필요.

**설정 조정:**
- `validateAfterInactivitySeconds: 30` → ✅ 적용 완료 (`application.yml`)
- DB를 MySQL로 교체 후 재측정하면 풀링 효과가 더 순수하게 드러날 것.
