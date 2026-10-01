/**
 * Smoke Test: 풀링 동작 여부 사전 검증
 *
 * 목적: 본 테스트 전에 현재 프로파일에서 커넥션 풀링이 실제로 동작하는지 확인한다.
 *
 * 실행 방법:
 *   # no-pool 프로파일
 *   ./gradlew bootRun --args='--spring.profiles.active=perf,perf-no-pool' 2>&1 | tee /tmp/app-no-pool.log &
 *   K6_SCRIPT=00_smoke-verify.js docker compose -f docker-compose.perf.yml run --rm k6
 *   grep -c "Connection leased from pool" /tmp/app-no-pool.log   # → 0 이어야 함
 *
 *   # pool 프로파일
 *   ./gradlew bootRun --args='--spring.profiles.active=perf' 2>&1 | tee /tmp/app-pool.log &
 *   K6_SCRIPT=00_smoke-verify.js docker compose -f docker-compose.perf.yml run --rm k6
 *   grep -c "Connection leased from pool" /tmp/app-pool.log      # → > 0 이어야 함
 *
 * 풀링 동작 여부 판단 기준 (앱 로그):
 *
 *   [풀 작동 시 나타나는 로그 패턴]
 *   PoolingHttpClientConnectionManager - Connection leased from pool [id: N][route: {tls}->https://httpbin.org:443]
 *   PoolingHttpClientConnectionManager - Connection released to pool [id: N][route: ...]
 *   → 같은 id가 반복되면 커넥션 재사용, 매번 다른 id면 재사용 안 됨
 *
 *   [no-pool(SimpleClientHttpRequestFactory) 시 나타나는 로그 패턴]
 *   → PoolingHttpClientConnectionManager 로그 자체가 없음
 *   → JVM SSL 로그(-Djavax.net.debug=ssl:handshake)를 켜면 매 요청마다 "ClientHello" 출력
 *
 * k6 출력에서 볼 수 있는 간접 지표:
 *   - http_req_connecting: k6→앱 커넥션 시간 (앱→httpbin.org와는 무관)
 *   - 따라서 k6 timings만으로는 앱의 아웃바운드 풀링 여부를 직접 판단할 수 없음
 *   - 반드시 앱 로그와 함께 봐야 함
 */

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EMAIL = __ENV.EMAIL || 'hihi@naver.com';
const PASSWORD = __ENV.PASSWORD || 'hihi';

export const options = {
  scenarios: {
    smoke: {
      executor: 'per-vu-iterations',
      vus: 1,       // 단일 VU: 순차 요청 → 커넥션 재사용 여부가 가장 명확히 드러남
      iterations: 5,
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
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
  const iter = __ITER;

  const payload = JSON.stringify({
    date: addDays('2027-01-01', iter),
    timeId: (iter % 6) + 1,
    themeId: (iter % 10) + 1,
    providerName: 'TOSS',
    paymentKey: `smoke-key-${iter}-${Date.now()}`,
    orderId: `smoke-order-${iter}-${Date.now()}`,
    amount: 10000,
  });

  const res = http.post(`${BASE_URL}/reservations`, payload, {
    headers: { 'Content-Type': 'application/json', Cookie: `token=${data.token}` },
  });

  check(res, { [`iter ${iter}: status 201`]: r => r.status === 201 });

  // 각 요청의 타이밍 출력 (참고용; k6→앱 구간이지 앱→httpbin.org 구간이 아님)
  console.log(
    `[iter ${iter}] status=${res.status} | total=${res.timings.duration}ms` +
    ` | waiting=${res.timings.waiting}ms (httpbin.org 딜레이 포함 가능)`
  );

  sleep(0.5); // 요청 간 간격: 커넥션이 pool로 반납된 후 다음 요청에서 재사용되는지 확인
}

function addDays(dateStr, days) {
  const d = new Date(dateStr);
  d.setDate(d.getDate() + days);
  return d.toISOString().split('T')[0];
}
