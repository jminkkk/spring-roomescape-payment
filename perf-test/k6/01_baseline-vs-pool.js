/**
 * Scenario 01: Baseline(no-pool) vs Pool 비교
 *
 * 목적: SimpleClientHttpRequestFactory와 PoolingHttpClientConnectionManager의 성능 차이 수치화
 *       원본 Java 테스트 결과(순차 14%, 동시 2.5x)를 k6로 재검증
 *
 * 실행 방법 (앱 3회 재시작):
 *   # Step 1: Baseline 1 — no-pool (SimpleClientHttpRequestFactory)
 *   ./gradlew bootRun --args='--spring.profiles.active=perf,perf-no-pool'
 *   K6_SCRIPT=01_baseline-vs-pool.js docker compose -f docker-compose.perf.yml run --rm k6
 *   # → 결과 기록
 *
 *   # Step 2: Baseline 2 — default-pool (Apache HttpClient, 최적화 옵션 ALL OFF)
 *   ./gradlew bootRun --args='--spring.profiles.active=perf,perf-default-pool'
 *   K6_SCRIPT=01_baseline-vs-pool.js docker compose -f docker-compose.perf.yml run --rm k6
 *   # → 결과 기록 (B1 대비 풀링 자체 효과 측정)
 *
 *   # Step 3: Current — 최적화 설정 (LIFO+STRICT+TTL+AIMD)
 *   ./gradlew bootRun --args='--spring.profiles.active=perf'
 *   K6_SCRIPT=01_baseline-vs-pool.js docker compose -f docker-compose.perf.yml run --rm k6
 *   # → 결과 비교 (B2 대비 최적화 설정 효과 측정)
 *
 * 주목할 지표:
 *   - p50, p95, p99 latency 비교
 *   - 앱 로그의 SSL handshake 횟수 (no-pool에서 매 요청마다 handshake 발생 예상)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EMAIL = __ENV.EMAIL || 'hihi@naver.com';
const PASSWORD = __ENV.PASSWORD || 'hihi';

const reservationLatency = new Trend('reservation_latency', true);
const errors = new Counter('errors');

export const options = {
  scenarios: {
    // 순차적 느린 ramp-up: TLS handshake 비용이 cumulative하게 드러남
    sequential_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 30,
      stages: [
        { duration: '10s', target: 5 },
        { duration: '30s', target: 20 },  // 안정 부하 구간
        { duration: '10s', target: 30 },
        { duration: '20s', target: 30 },  // 최대 부하 유지
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    reservation_latency: ['p(95)<5000'],
    http_req_failed: ['rate<0.10'],
  },
};

export function setup() {
  const loginRes = http.post(
    `${BASE_URL}/login`,
    JSON.stringify({ email: EMAIL, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (loginRes.status !== 200) {
    throw new Error(`Login failed: ${loginRes.status}`);
  }
  const tokenCookie = Object.values(loginRes.cookies).flat().find(c => c.name === 'token');
  if (!tokenCookie) throw new Error('Token cookie not found');
  return { token: tokenCookie.value };
}

export default function (data) {
  const vuId = __VU;
  const iter = __ITER;

  const themeId = ((vuId - 1) % 10) + 1;
  const timeId = ((vuId - 1) % 6) + 1;
  const futureDate = addDays('2026-06-01', iter + vuId * 300);

  const payload = JSON.stringify({
    date: futureDate,
    timeId,
    themeId,
    providerName: 'TOSS',
    paymentKey: `baseline-key-${vuId}-${iter}-${Date.now()}`,
    orderId: `baseline-order-${vuId}-${iter}-${Date.now()}`,
    amount: 10000,
  });

  const res = http.post(`${BASE_URL}/reservations`, payload, {
    headers: { 'Content-Type': 'application/json', Cookie: `token=${data.token}` },
  });

  reservationLatency.add(res.timings.duration);

  const ok = check(res, { 'status 201': r => r.status === 201 });
  if (!ok) errors.add(1);

  sleep(0.05);
}

function addDays(dateStr, days) {
  const d = new Date(dateStr);
  d.setDate(d.getDate() + days);
  return d.toISOString().split('T')[0];
}
