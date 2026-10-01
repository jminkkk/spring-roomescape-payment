/**
 * Scenario 06: Default Pool vs Optimal Pool — 실제 Toss 테스트 서버 기준
 *
 * 목적:
 *   VU를 50 → 200까지 ramp-up하며 커넥션 풀 설정이 latency/에러율에 미치는 영향 수치화.
 *   WireMock(고정 200ms) 대신 실제 Toss 테스트 서버 응답시간 기준으로 측정.
 *
 * ──────────────────────────────────────────────────────────────────────
 * 주의: payment.pool.* yml 설정은 현재 PaymentProperties에 매핑되지 않아 무시됨.
 *       pool 설정은 PaymentRestClientConfiguration.java를 직접 수정해야 함.
 * ──────────────────────────────────────────────────────────────────────
 *
 * 실행 방법:
 *
 *   [1회차] 기본값 (PaymentRestClientConfiguration.java 수정 필요)
 *     - setMaxConnPerRoute(50)
 *     - setConnectionRequestTimeout(Timeout.ofSeconds(10))
 *   ./gradlew bootRun --args='--spring.profiles.active=perf'
 *   K6_SCRIPT=06_default-vs-optimal-toss.js docker compose -f docker-compose.perf.yml run --rm k6
 *
 *   [2회차] 최적값 (현재 코드 기준, 수정 불필요)
 *     - setMaxConnPerRoute(100)
 *     - setConnectionRequestTimeout(Timeout.ofSeconds(5))
 *   ./gradlew bootRun --args='--spring.profiles.active=perf'
 *   K6_SCRIPT=06_default-vs-optimal-toss.js docker compose -f docker-compose.perf.yml run --rm k6
 *
 * 주목할 지표:
 *   - reservation_latency p95, p99: VU가 pool 크기를 초과하는 구간에서 차이 발생
 *   - pool_timeout_errors: connectionRequestTimeout 초과 시 카운트
 *   - http_req_failed: 전체 에러율 (Toss 4xx 포함 — 커넥션 풀과 무관한 비즈니스 에러)
 *
 * 판단 기준:
 *   VU=100 구간:
 *     기본값(pool=50): 50 VU가 풀 대기 → p95 latency 증가 예상
 *     최적값(pool=100): 대기 없음 → p95 낮음
 *   VU=200 구간:
 *     기본값: 150 VU 대기, connectionRequestTimeout=10s → 큐 적체
 *     최적값: 100 VU 대기, connectionRequestTimeout=5s → 빠른 실패 또는 빠른 처리
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EMAIL = __ENV.EMAIL || 'hihi@naver.com';
const PASSWORD = __ENV.PASSWORD || 'hihi';

const reservationLatency = new Trend('reservation_latency', true);
const poolTimeoutErrors = new Counter('pool_timeout_errors');

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 50 },   // ramp-up
        { duration: '30s', target: 50 },   // VU=50: pool=50 기본값에서 경합 시작 직전
        { duration: '15s', target: 100 },  // ramp-up
        { duration: '30s', target: 100 },  // VU=100: 기본값(pool=50) → 50 VU 대기
        { duration: '15s', target: 150 },  // ramp-up
        { duration: '30s', target: 150 },  // VU=150: 기본값 → 100 VU 대기
        { duration: '15s', target: 200 },  // ramp-up
        { duration: '30s', target: 200 },  // VU=200: 기본값 → 150 VU 대기, timeout 에러 예상
        { duration: '15s', target: 0 },    // cool-down
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // 기준 완화 (Toss 실제 응답시간 미확정 + 비즈니스 4xx 포함)
    reservation_latency: ['p(95)<15000'],
    http_req_failed: ['rate<0.80'],
  },
};

export function setup() {
  const loginRes = http.post(
    `${BASE_URL}/login`,
    JSON.stringify({ email: EMAIL, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (loginRes.status !== 200) {
    throw new Error(`Login failed: ${loginRes.status} ${loginRes.body}`);
  }
  const tokenCookie = Object.values(loginRes.cookies).flat().find(c => c.name === 'token');
  if (!tokenCookie) throw new Error('Token cookie not found');
  return { token: tokenCookie.value };
}

export default function (data) {
  const vuId = __VU;
  const iter = __ITER;
  const now = Date.now();

  // themeId 1-10, timeId 1-6 순환. 날짜를 VU/iter 기준으로 분산해 슬롯 충돌 방지.
  const themeId = ((vuId - 1) % 10) + 1;
  const timeId = ((vuId - 1) % 6) + 1;
  const futureDate = addDays('2030-01-01', (vuId * 500) + iter);

  const payload = JSON.stringify({
    date: futureDate,
    timeId,
    themeId,
    providerName: 'TOSS',
    paymentKey: `toss-ramp-${vuId}-${iter}-${now}`,
    orderId: `order-ramp-${vuId}-${iter}-${now}`,
    amount: 10000,
  });

  const res = http.post(`${BASE_URL}/reservations`, payload, {
    headers: {
      'Content-Type': 'application/json',
      Cookie: `token=${data.token}`,
    },
    timeout: '20s',
  });

  reservationLatency.add(res.timings.duration);

  check(res, {
    'status 2xx or 4xx (not timeout)': r => r.status !== 0 && r.status !== 503,
  });

  // 커넥션 풀 고갈로 인한 timeout: status=0 (연결 자체 실패) 또는 앱이 503 반환
  if (res.status === 0 || res.status === 503) {
    poolTimeoutErrors.add(1);
    console.log(`[VU=${vuId}] pool timeout or connection error: status=${res.status}`);
  }
}

function addDays(dateStr, days) {
  const d = new Date(dateStr);
  d.setDate(d.getDate() + days);
  return d.toISOString().split('T')[0];
}