/**
 * Scenario 02: LIFO vs FIFO 비교
 *
 * 목적: LIFO가 "최근 커넥션 재사용 → TLS session 재활용"에서 FIFO보다 유리한지 검증
 *
 * 실행 방법 (각각 앱 재시작 후 동일 스크립트 실행):
 *   # LIFO (현재 설정, 기본 perf 프로파일)
 *   ./gradlew bootRun --args='--spring.profiles.active=perf'
 *   K6_SCRIPT=02_lifo-vs-fifo.js docker compose -f docker-compose.perf.yml run --rm k6
 *
 *   # FIFO (비교군)
 *   ./gradlew bootRun --args='--spring.profiles.active=perf,perf-fifo'
 *   K6_SCRIPT=02_lifo-vs-fifo.js docker compose -f docker-compose.perf.yml run --rm k6
 *
 * TLS handshake 카운트 방법 (앱 로그):
 *   ./gradlew bootRun --args='--spring.profiles.active=perf' \
 *     2>&1 | grep -c "TLS handshake\|Received ServerHello\|SSL_connect"
 *
 * 또는 JVM 플래그로 상세 SSL 로그 활성화:
 *   ./gradlew bootRun \
 *     --args='--spring.profiles.active=perf' \
 *     -Djavax.net.debug=ssl:handshake
 *   # 로그에서 "ClientHello" 발생 횟수가 full handshake 수
 *
 * 주목할 지표:
 *   - p50/p95 latency: LIFO는 세션 재사용으로 낮을 것으로 예상
 *   - "ClientHello" 로그 발생 수: FIFO에서 더 많이 발생 예상
 *
 * 주의: 동시 부하가 낮으면 LIFO/FIFO 차이가 드러나지 않음.
 *       idle 커넥션이 많이 생기는 moderate load(VU=15)에서 차이가 두드러짐.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EMAIL = __ENV.EMAIL || 'hihi@naver.com';
const PASSWORD = __ENV.PASSWORD || 'hihi';

const reservationLatency = new Trend('reservation_latency', true);
const errors = new Counter('errors');
const newConnections = new Counter('new_connections'); // res.timings.connecting > 0 일 때 카운트 (TLS handshake 발생 추정)

export const options = {
  scenarios: {
    moderate_load: {
      executor: 'constant-vus',
      vus: 15,         // 풀 크기(50)보다 훨씬 작음 → idle 커넥션 많이 생김 → LIFO/FIFO 차이 드러남
      duration: '60s',
      gracefulStop: '5s',
    },
  },
  thresholds: {
    reservation_latency: ['p(95)<3000'],
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
  const futureDate = addDays('2026-07-01', iter + vuId * 400);

  const payload = JSON.stringify({
    date: futureDate,
    timeId,
    themeId,
    providerName: 'TOSS',
    paymentKey: `lifo-key-${vuId}-${iter}-${Date.now()}`,
    orderId: `lifo-order-${vuId}-${iter}-${Date.now()}`,
    amount: 10000,
  });

  const res = http.post(`${BASE_URL}/reservations`, payload, {
    headers: { 'Content-Type': 'application/json', Cookie: `token=${data.token}` },
  });

  reservationLatency.add(res.timings.duration);

  // 응답 시간 중 connecting 시간 별도 추적 (TLS handshake 비용 포함)
  // res.timings.connecting이 0이면 커넥션 재사용(handshake 없음)
  // res.timings.connecting > 0이면 새 커넥션 생성(handshake 발생)
  // res.timings.connecting: k6→앱 구간이므로 앱→httpbin.org TLS handshake와는 다름
  // 그러나 같은 패턴 (0이면 재사용, >0이면 새 TCP 연결)으로 간접 참고 가능
  if (res.timings.connecting > 0) {
    newConnections.add(1);
  }

  check(res, { 'status 201': r => r.status === 201 });

  // think time 없음: 커넥션이 idle로 돌아가는 상황을 만들어야 LIFO/FIFO 차이가 생김
  sleep(0.5);  // 0.5s idle → 이 커넥션을 다음 요청에서 LIFO는 재사용, FIFO는 방치
}

function addDays(dateStr, days) {
  const d = new Date(dateStr);
  d.setDate(d.getDate() + days);
  return d.toISOString().split('T')[0];
}
