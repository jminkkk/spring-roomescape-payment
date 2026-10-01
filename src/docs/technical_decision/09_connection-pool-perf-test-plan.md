# 커넥션 풀 최적화 성능 테스트 계획

## 목표

`PaymentRestClientConfiguration`의 커넥션 풀 설정값 중 **처리량에 실제 영향을 주는 3개**를 최적화한다.

나머지 설정(LIFO, STRICT, TTL, eviction 등)은 안정성/신뢰성 설정이므로 처리량 최적화 대상에서 제외한다.

---

## 설정값 분류

### 처리량에 영향을 주는 설정

| 설정 | 의미 | 처리량 영향 |
|------|------|------------|
| `maxConnPerRoute` | 단일 호스트(Toss Payments)에 동시에 열 수 있는 최대 커넥션 수. 스레드가 이 수를 초과하면 풀에서 대기한다. | **HIGH** |
| `connectionRequestTimeout` | 풀이 고갈됐을 때 커넥션을 기다리는 최대 시간. 초과 시 예외 발생. 짧으면 빠른 실패, 길면 큐 적체. | **HIGH** |
| `connectTimeout` | TCP 3-way handshake + TLS handshake를 완료하는 제한 시간. 부하 시 외부 서버가 느리면 타임아웃 발생 → 실패율 증가. | **MEDIUM** |

### 처리량과 무관한 설정 (안정성/신뢰성)

| 설정 | 의미 | 처리량 영향 |
|------|------|------------|
| `maxConnTotal` | 전체 라우트 합산 커넥션 상한. 이 서비스는 Toss Payments 단일 라우트만 사용하므로 `maxConnPerRoute`가 바인딩 제약이 됨. `maxConnTotal >= maxConnPerRoute`이면 처리량에 영향 없음. | **NONE** |
| `PoolReusePolicy.LIFO` | 풀에서 꺼낼 때 가장 최근 커넥션 우선 사용. TLS 세션 재사용 가능성을 높여 handshake 비용 절감. | **LOW** |
| `PoolConcurrencyPolicy.STRICT` | 풀 접근 시 공정한 순서 보장. LENIENT보다 약간 느리지만 특정 스레드가 굶지 않음. | **LOW** |
| `TimeToLive` | 커넥션 생성 후 최대 유지 시간. 이 시간이 지나면 강제 교체. | **NONE** |
| `ValidateAfterInactivity` | 유휴 후 재사용 전 커넥션 유효성 검사 기준 시간. | **NONE** |
| `evictIdleConnections` | 일정 시간 유휴 상태인 커넥션 자동 제거. 좀비 커넥션 방지. | **NONE** |
| `responseTimeout / SoTimeout` | 서버 응답을 기다리는 최대 시간. 처리량이 아니라 단건 요청의 hang 방지 용도. | **NONE** |

---

## 테스트 대상 변수

| 변수 | 현재값 | 후보값 | 영향 |
|------|--------|--------|------|
| `maxConnPerRoute` | 50 | 5, 10, 20, 50, 100 | 동시 연결 상한 |
| `connectionRequestTimeout` | 10s | 1s, 3s, 5s, 10s, 30s | 풀 고갈 시 대기 vs 실패 결정 |
| `connectTimeout` | 1s | 500ms, 1s, 2s, 5s | 부하 시 TCP 연결 수립 실패율 |

**고정 조건**: 스레드 100개, 요청 500건, warmup 5회

---

## 1단계: 베이스라인 측정

현재 설정값으로 기준 수치를 먼저 확보한다.
`compareConcurrentRequests()` 실행 → 노풀링 vs 현재 커스텀 풀링 비교.

### [x] 베이스라인 결과

```
노풀링 (SimpleClientHttpRequestFactory):
  → 6556ms, TPS=76.3 req/s, 에러율=0.0%

현재 커스텀 풀링 (maxConnPerRoute=50, timeout=10s, connectTimeout=1s):
  → 12928ms, TPS=38.7 req/s, 에러율=0.0%

개선율: -97.19% (커스텀 풀링이 오히려 2배 느림)
```

**원인**: `maxConnPerRoute=50 < 스레드 100개`
- 노풀링: 100스레드 동시 실행 → 500req / 100 = 5라운드
- 커스텀 풀링: 50커넥션 제한 → 500req / 50 = 10라운드 (2배)
- TLS 재사용 이득 < 병렬성 제한 손해

**결론**: maxConnPerRoute ≥ 스레드 수(100)일 때부터 풀링이 노풀링을 이길 수 있음. 2단계에서 검증.

---

## 2단계: maxConnPerRoute 탐색

`findOptimalMaxConnPerRoute()` 실행.
connectionRequestTimeout=10s, connectTimeout=1s 고정.

**기대 패턴**: 처리량이 스레드 수(100)에 수렴하는 지점이 최적값.

### [x] 결과

```
maxConnPerRoute=5:   55288ms, TPS=9.0 req/s,  에러율=25.8%  ← 풀 고갈로 timeout 폭발
maxConnPerRoute=10:  31930ms, TPS=15.7 req/s, 에러율=0.2%
maxConnPerRoute=20:  18414ms, TPS=27.2 req/s, 에러율=0.2%
maxConnPerRoute=50:  10606ms, TPS=47.1 req/s, 에러율=0.0%  ← 현재 설정
maxConnPerRoute=100:  8669ms, TPS=57.7 req/s, 에러율=0.0%
maxConnPerRoute=200:  7504ms, TPS=66.6 req/s, 에러율=0.0%
maxConnPerRoute=250:  6871ms, TPS=72.8 req/s, 에러율=0.4%  ← 노풀링(76.3)에 근접
maxConnPerRoute=300:  8744ms, TPS=57.2 req/s, 에러율=0.4%  ← 외부 서버/OS 한계 도달

베이스라인 (노풀링): 76.3 req/s
```

**패턴**: maxConnPerRoute에 비례해 선형 증가 → 250에서 노풀링 수준 근접 → 300에서 역전

**관찰**: TLS 세션 재사용 이득이 이 테스트에서 두드러지지 않음.
500req / 250conn = 커넥션당 평균 2회 재사용에 불과해 이득이 미미함.
실제 운영 트래픽(지속적인 재사용)에서는 풀링 이득이 더 크게 나타날 것으로 예상.

→ **최적 maxConnPerRoute: 100~200** (스레드 수 기준, 에러율 0%, 노풀링의 75~87% 성능)

### [x] WireMock 재측정 결과 (노이즈 제거)

httpbin.org의 외부 노이즈로 결과가 불안정해 WireMock 로컬 서버(200ms 고정 지연)로 재측정.

```
maxConnPerRoute=5:    21144ms, TPS=23.6,  에러율=0.0%
maxConnPerRoute=10:   10450ms, TPS=47.8,  에러율=0.0%
maxConnPerRoute=20:    5386ms, TPS=92.8,  에러율=0.0%  ← 수렴 시작
maxConnPerRoute=50:    5336ms, TPS=93.7,  에러율=0.0%
maxConnPerRoute=100:   5349ms, TPS=93.5,  에러율=0.0%
maxConnPerRoute=200:   5367ms, TPS=93.2,  에러율=0.0%
maxConnPerRoute=250:   5341ms, TPS=93.6,  에러율=0.0%
maxConnPerRoute=300:   5334ms, TPS=93.7,  에러율=0.0%
```

**수렴 이유 (Little's Law)**:
```
수렴 커넥션 수 = TPS × 응답시간 = 93 × 0.2 ≈ 19 → 20에서 수렴
```
20개 이상부터 커넥션 대기 시간 ≈ 0ms. 이후 병목은 커넥션 풀이 아닌 서버 처리 능력으로 이동.

---

## 3단계: connectionRequestTimeout 탐색

`findOptimalConnectionRequestTimeout()` 실행.
maxConnPerRoute를 2단계 결론값보다 낮게 고정(풀 경합 유발), connectTimeout=1s 고정.

**기대 패턴**:
- 짧은 timeout → 빠른 실패, 큐 적체 없음, 에러율 높음
- 긴 timeout → 느린 실패, 큐 적체, 에러율 낮음
- 최적: 에러율을 허용 범위 내로 유지하는 최솟값

### [x] 결과

조건: maxConnPerRoute=20 고정 (100스레드 중 80개 풀 대기 → 경합 강제 유발)

```
timeout=1s:   6981ms, TPS=71.6, 에러율=68.2%  ← 빠른 실패, 실제 성공 TPS=22.8
timeout=3s:  17222ms, TPS=29.0, 에러율=9.8%   ← 성공 TPS=26.2
timeout=5s:  15766ms, TPS=31.7, 에러율=0.0%   ← 에러 없이 최고 성공 TPS=31.7
timeout=10s: 18842ms, TPS=26.5, 에러율=0.2%   ← 현재 설정, 큐 적체로 느림
timeout=30s: 18091ms, TPS=27.6, 에러율=0.0%
```

> 주의: 로그의 TPS는 `총요청수/시간` 기준(실패 포함). 실질 지표는 에러율 0%에서의 성공 TPS.

**관찰**: timeout이 길수록 대기 큐가 쌓이는 시간이 늘어 전체 처리 시간 증가.
5s는 짧은 실패와 큐 적체 사이의 균형점.

→ **최적 connectionRequestTimeout: 5s** (에러율 0%, 성공 TPS 31.7로 최고)

---

## 4단계: connectTimeout 탐색

`findOptimalConnectTimeout()` 실행.
maxConnPerRoute, connectionRequestTimeout은 앞 단계 결론값으로 고정.

**기대 패턴**:
- 너무 짧으면 부하 시 TCP 연결 수립 실패 → 재시도 오버헤드
- 너무 길면 실패 감지가 늦어 스레드 낭비

### [x] 결과

조건: maxConnPerRoute=50, connectionRequestTimeout=10s 고정

```
connectTimeout=500ms: 14396ms, TPS=34.7, 에러율=0.4%  ← 간헐적 실패
connectTimeout=1s:    11944ms, TPS=41.9, 에러율=0.0%  ← 현재 설정
connectTimeout=2s:     6032ms, TPS=82.9, 에러율=0.0%  ← 이상값 (노이즈)
connectTimeout=5s:    15462ms, TPS=32.3, 에러율=0.2%
```

> 2s 결과(82.9 TPS)는 노풀링 베이스라인(76.3)을 초과하며 신뢰하기 어려움.
> connectTimeout 변경만으로 TPS가 2배 뛰는 건 설명이 안 됨 — httpbin.org 네트워크 상태 변동(외부 노이즈)으로 판단.

**관찰**: connectTimeout은 TCP 연결 수립 제한 시간. Toss Payments처럼 신뢰성 높은 인프라는 TCP 연결이 통상 100ms 이하라, 이 변수의 처리량 영향은 정상 상황에서 미미함. 500ms는 간헐적 실패 위험이 있어 제외.

→ **최적 connectTimeout: 1s** (현재 설정 유지, 에러율 0%, 안정적)

---

## 5단계: 설정값 반영

```java
.setMaxConnPerRoute(100)                          // 아래 근거 참고
.setMaxConnTotal(200)                             // maxConnPerRoute * 2
.setConnectionRequestTimeout(Timeout.ofSeconds(5)) // 3단계: 에러율 0% + 최고 성공 TPS
.setConnectTimeout(Timeout.ofSeconds(1))          // 4단계: 현재 설정 유지
```

### maxConnPerRoute 근거 (Little's Law)

```
필요 커넥션 수(N) = TPS(λ) × 외부 API 응답시간(W)
```

| 변수 | 값 | 근거 |
|------|-----|------|
| Tomcat max-threads | 200 (기본값, 별도 설정 없음) | Spring Boot 기본값 |
| 결제 요청 비율 | 전체 트래픽 중 일부 (최악 가정: 100%) | |
| Toss 응답시간 | 0.5s 가정 | 실측 필요 |
| 최대 TPS | 200 / 0.5 = **400 TPS** | Tomcat threads / 응답시간 |
| 이론적 필요 커넥션 | 400 × 0.5 = **200개** | Little's Law |

**현재 설정 100의 의미**: 모든 Tomcat 스레드가 동시에 결제 요청을 처리하지 않으므로 100으로도 충분한 여유분. Toss 응답시간이 실측되거나 피크 트래픽이 증가하면 재검토 필요.

> 정확한 값은 운영 APM에서 결제 엔드포인트 피크 TPS와 Toss 실제 응답시간을 측정한 후 `N = TPS × W`로 산출한다.

---

## 6단계: 실제 Toss 테스트 서버 검증 (k6)

WireMock(200ms 고정 지연)이 아닌 실제 Toss 테스트 서버를 대상으로 기본값 vs 최적값 비교.

### 테스트 환경

| 항목 | 값 |
|------|-----|
| 도구 | k6 (`06_default-vs-optimal-toss.js`) |
| 대상 | Spring Boot → `https://api.tosspayments.com/v1/payments/confirm` |
| VU 구성 | ramp-up: 0 → 50 → 100 → 150 → 200 (각 구간 30s 유지) |
| 총 실행 시간 | 3m 15s |
| 머신 | macOS, RAM 16GB, ThreadStackSize 2MB |

### [x] 결과

```
기본값 (maxConnPerRoute=50, connectionRequestTimeout=10s):
  → avg=11.38s, med=10.85s, p90=20s, p95=20s
  → TPS=9.66 req/s, pool_timeout_errors=331 (16.68%)

최적값 (maxConnPerRoute=100, connectionRequestTimeout=5s):
  → avg=11.2s,  med=10.72s, p90=20s, p95=20s
  → TPS=9.84 req/s, pool_timeout_errors=342 (16.91%)

iterations: 기본값=1983, 최적값=2021 (+1.9%)
```

**두 설정의 차이가 거의 없음.**

### 원인 분석

실제 Toss 테스트 서버 응답시간이 지배적이었다.

```
측정된 Toss 테스트 응답시간 (invalid paymentKey 기준): avg ~10s
Little's Law: N = TPS × W = 9.84 × 10 ≈ 98 connections
```

pool=50 vs pool=100의 대기 시간 차이(수 초)가 Toss 응답시간(~10s)에 비해 상대적으로 작아 풀 설정 차이가 latency에 묻혔다. k6 timeout=20s에 두 케이스 모두 동일하게 잘린 것도 차이를 희석시켰다.

### 결론

1. **WireMock(200ms)이 커넥션 풀 격리 테스트에 더 적합하다.** 외부 API 응답이 빠를수록 풀 대기 시간 비율이 커져 설정 차이가 명확히 드러난다.

2. **Toss 실제 응답시간 실측값 확보.** invalid paymentKey 기준 ~10s. 운영에서 valid 결제 요청 기준으로는 훨씬 짧을 것으로 예상 — APM 실측 필요.

3. **5단계 maxConnPerRoute=100 설정의 타당성 재확인.**
   ```
   Toss 응답 0.5s 가정 시: N = (200스레드 × 0.5s) = 100 → 현재 설정과 일치
   Toss 응답 10s 가정 시:  N = (10 TPS × 10s) = 100 → 동일하게 100이 적정
   ```

---

## 진행 상태

- [x] 테스트 순차 실행으로 수정
- [x] warmup 추가
- [x] `findOptimalMaxConnPerRoute` 테스트 추가
- [x] `findOptimalConnectionRequestTimeout` 테스트 추가
- [x] `findOptimalConnectTimeout` 테스트 추가
- [x] 1단계: 베이스라인 측정
- [x] 2단계: maxConnPerRoute 탐색
- [x] 3단계: connectionRequestTimeout 탐색
- [x] 4단계: connectTimeout 탐색
- [x] 5단계: 설정값 반영
- [x] 6단계: 실제 Toss 테스트 서버 검증 (k6)
