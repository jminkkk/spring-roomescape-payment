/**
 * Scenario 04: maxConnPerRoute 적정성 검증 (ramp-up)
 *
 * 목적: maxConnPerRoute=50이 실제로 병목이 되는 지점을 탐색
 *       VU가 50 초과 시 connectionRequestTimeout(1s) 에러 발생 여부 확인
 *
 * 실행:
 *   ./gradlew bootRun --args='--spring.profiles.active=perf'
 *   K6_SCRIPT=04_pool-size-ramp.js docker compose -f docker-compose.perf.yml run --rm k6
 *
 * 주목할 지표:
 *   - VU=10, 30, 50, 60 각 구간의 p95 latency
 *   - VU=60 구간에서 error rate 및 에러 유형 확인
 *   - 앱 로그: "Timeout waiting for connection from pool" 발생 여부
 *
 * 판단 기준:
 *   VU=60에서 에러 없음 → maxConnPerRoute=50 여유 있음 (기다리면 얻을 수 있는 상황)
 *   VU=60에서 timeout 에러 → maxConnPerRoute 상향 또는 connectionRequestTimeout 조정 필요
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EMAIL = __ENV.EMAIL || 'hihi@naver.com';
const PASSWORD = __ENV.PASSWORD || 'hihi';

const reservationLatency = new Trend('reservation_latency', true);
const poolErrors = new Counter('pool_timeout_errors');

export const options = {
  scenarios: {
    pool_ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 10 },
        { duration: '20s', target: 10 },  // 안정 구간 (< 50)
        { duration: '10s', target: 30 },
        { duration: '20s', target: 30 },  // 중간 부하
        { duration: '10s', target: 50 },
        { duration: '20s', target: 50 },  // 풀 한계 직전
        { duration: '10s', target: 60 },
        { duration: '30s', target: 60 },  // 풀 초과 (connectionRequestTimeout 트리거 예상)
        { duration: '10s', target: 0 },   // 회복 관찰
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    reservation_latency: ['p(95)<6000'],
    http_req_failed: ['rate<0.15'],
  },
};

export function setup() {
  const loginRes = http.post(
    `${BASE_URL}/login`,
    JSON.stringify({ email: EMAIL, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (loginRes.status !== 200) throw new Error(`Login failed: ${loginRes.status}`);
  const tokenCookie = Object.values(loginRes.cookies).flat().find(c => c.name === 'token');
  if (!tokenCookie) throw new Error('Token cookie not found');
  return { token: tokenCookie.value };
}

export default function (data) {
  const vuId = __VU;
  const iter = __ITER;

  const themeId = ((vuId - 1) % 10) + 1;
  const timeId = ((vuId - 1) % 6) + 1;
  const futureDate = addDays('2026-11-01', iter + vuId * 600);

  const payload = JSON.stringify({
    date: futureDate,
    timeId,
    themeId,
    providerName: 'TOSS',
    paymentKey: `ramp-key-${vuId}-${iter}-${Date.now()}`,
    orderId: `ramp-order-${vuId}-${iter}-${Date.now()}`,
    amount: 10000,
  });

  const res = http.post(`${BASE_URL}/reservations`, payload, {
    headers: { 'Content-Type': 'application/json', Cookie: `token=${data.token}` },
    timeout: '10s',
  });

  reservationLatency.add(res.timings.duration);

  const ok = check(res, { 'status 201': r => r.status === 201 });
  if (!ok && (res.status === 0 || res.status === 503)) {
    // 커넥션 풀 고갈로 인한 타임아웃/거부 추정
    poolErrors.add(1);
  }
}

function addDays(dateStr, days) {
  const d = new Date(dateStr);
  d.setDate(d.getDate() + days);
  return d.toISOString().split('T')[0];
}
