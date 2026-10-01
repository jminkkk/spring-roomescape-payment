/**
 * Scenario 03: STRICT vs LENIENT 동시성 정책 비교
 *
 * 목적: STRICT(현재 설정)가 "예측 가능한 Latency"를 보장하는지,
 *       즉 p99가 LENIENT보다 안정적인지 검증
 *
 * 트레이드오프 확인:
 *   - STRICT: 공정한 대기 큐 → p99 낮음, 평균은 다소 높을 수 있음
 *   - LENIENT: 먼저 잡는 스레드 우선 → 평균 latency는 낮지만 p99가 튈 수 있음
 *
 * 실행 방법:
 *   # STRICT (기본 perf 프로파일)
 *   ./gradlew bootRun --args='--spring.profiles.active=perf'
 *   K6_SCRIPT=03_strict-vs-lenient.js docker compose -f docker-compose.perf.yml run --rm k6
 *
 *   # LENIENT (비교군)
 *   ./gradlew bootRun --args='--spring.profiles.active=perf,perf-lenient'
 *   K6_SCRIPT=03_strict-vs-lenient.js docker compose -f docker-compose.perf.yml run --rm k6
 *
 * 주목할 지표:
 *   - p50: 두 설정의 "평균 성능" → LENIENT가 낮을 수 있음
 *   - p99: 결제 서버 안정성 핵심 지표 → STRICT가 낮아야 선택 근거 성립
 *   - max latency: 극단값 → LENIENT에서 starvation 발생 시 크게 튐
 *
 * 설계 근거:
 *   VU=40으로 풀 크기(50)에 근접하게 설정 → 커넥션 경합이 생겨야 두 정책의 차이가 드러남
 *   VU가 너무 낮으면 경합이 없어 차이가 없음
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EMAIL = __ENV.EMAIL || 'hihi@naver.com';
const PASSWORD = __ENV.PASSWORD || 'hihi';

const reservationLatency = new Trend('reservation_latency', true);

export const options = {
  scenarios: {
    high_concurrency: {
      executor: 'constant-vus',
      vus: 40,          // maxConnPerRoute(50)에 근접 → 커넥션 경합 발생
      duration: '60s',
      gracefulStop: '5s',
    },
  },
  thresholds: {
    reservation_latency: ['p(99)<5000'],
    http_req_failed: ['rate<0.05'],
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
  const futureDate = addDays('2026-09-01', iter + vuId * 200);

  const payload = JSON.stringify({
    date: futureDate,
    timeId,
    themeId,
    providerName: 'TOSS',
    paymentKey: `strict-key-${vuId}-${iter}-${Date.now()}`,
    orderId: `strict-order-${vuId}-${iter}-${Date.now()}`,
    amount: 10000,
  });

  const res = http.post(`${BASE_URL}/reservations`, payload, {
    headers: { 'Content-Type': 'application/json', Cookie: `token=${data.token}` },
    timeout: '10s',
  });

  reservationLatency.add(res.timings.duration);
  check(res, { 'status 201': r => r.status === 201 });

  // think time 없음: 지속적인 경합 상태 유지
}

function addDays(dateStr, days) {
  const d = new Date(dateStr);
  d.setDate(d.getDate() + days);
  return d.toISOString().split('T')[0];
}
