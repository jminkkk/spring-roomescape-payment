
 /**
 * 동기 vs 비동기 알림 성능 비교
 *
 * 목적: 예약 취소(= 대기자 승격) 시 알림 발송이 동기/비동기일 때
 *       API 응답 시간 차이를 측정한다.
 *
 * 시나리오:
 *   1. 사용자A(몰리)가 예약 생성
 *   2. 사용자B(포비)가 대기 등록
 *   3. 사용자A가 예약 취소 → 승격 + 알림 발송 트리거
 *   → 3번의 응답 시간을 측정
 *
 * 전제 조건:
 *   - perf 프로파일로 서버 기동 (SlowNotificationClient 활성화, 500ms 지연)
 *   - data.sql에 기본 회원/시간/테마 데이터 존재
 *
 * 실행 방법:
 *   # Before (동기)
 *   ./gradlew bootRun --args='--spring.profiles.active=perf' &
 *   k6 run perf-test/k6/05_sync-vs-async-notification.js
 *
 *   # After (비동기 전환 후)
 *   ./gradlew bootRun --args='--spring.profiles.active=perf' &
 *   k6 run perf-test/k6/05_sync-vs-async-notification.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 사용자A: 몰리 (id=1) — 예약자 (취소하는 사람)
const USER_A_EMAIL = 'hihi@naver.com';
const USER_A_PASSWORD = 'hihi';

// 사용자B: 포비 (id=3) — 대기자 (승격되는 사람)
const USER_B_EMAIL = 'test@naver.com';
const USER_B_PASSWORD = 'hihi';

// 커스텀 메트릭: 취소(승격) API 응답 시간만 별도 추적
const cancelDuration = new Trend('cancel_duration', true);

export const options = {
  scenarios: {
    promotion: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 20,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.1'],
  },
};

function login(email, password) {
  const res = http.post(
    `${BASE_URL}/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (res.status !== 200) throw new Error(`Login failed: ${res.status} - ${email}`);
  const tokenCookie = Object.values(res.cookies).flat().find(c => c.name === 'token');
  if (!tokenCookie) throw new Error('Token cookie not found');
  return tokenCookie.value;
}

export function setup() {
  const userAToken = login(USER_A_EMAIL, USER_A_PASSWORD);
  const userBToken = login(USER_B_EMAIL, USER_B_PASSWORD);
  return { userAToken, userBToken };
}

export default function (data) {
  const iter = __ITER;
  const date = addDays('2028-01-01', iter);
  const timeId = (iter % 6) + 1;
  const themeId = (iter % 10) + 1;

  const userAHeaders = {
    'Content-Type': 'application/json',
    Cookie: `token=${data.userAToken}`,
  };
  const userBHeaders = {
    'Content-Type': 'application/json',
    Cookie: `token=${data.userBToken}`,
  };

  // 1. 사용자A(몰리)가 예약 생성
  const createRes = http.post(
    `${BASE_URL}/reservations`,
    JSON.stringify({
      date,
      timeId,
      themeId,
      providerName: 'TOSS',
      paymentKey: `perf-key-${iter}-${Date.now()}`,
      orderId: `perf-order-${iter}-${Date.now()}`,
      amount: 10000,
    }),
    { headers: userAHeaders }
  );

  if (!check(createRes, { 'reservation created': r => r.status === 201 })) {
    console.log(`[iter ${iter}] 예약 생성 실패: ${createRes.status} ${createRes.body}`);
    return;
  }

  const reservationId = createRes.json('id');

  // 2. 사용자B(포비)가 대기 등록
  const waitingRes = http.post(
    `${BASE_URL}/waitings`,
    JSON.stringify({ date, timeId, themeId }),
    { headers: userBHeaders }
  );

  if (!check(waitingRes, { 'waiting created': r => r.status === 201 })) {
    console.log(`[iter ${iter}] 대기 등록 실패: ${waitingRes.status} ${waitingRes.body}`);
    return;
  }

  // 3. 사용자A가 예약 취소 → 승격 + 알림 트리거
  const cancelRes = http.del(
    `${BASE_URL}/reservations/${reservationId}`,
    null,
    { headers: userAHeaders }
  );

  check(cancelRes, { 'cancel succeeded': r => r.status === 204 });

  // 취소 API 응답 시간 기록
  cancelDuration.add(cancelRes.timings.duration);

  console.log(
    `[iter ${iter}] cancel: ${cancelRes.timings.duration.toFixed(1)}ms` +
    ` (waiting=${cancelRes.timings.waiting.toFixed(1)}ms)`
  );

  sleep(0.2);
}

function addDays(dateStr, days) {
  const d = new Date(dateStr);
  d.setDate(d.getDate() + days);
  return d.toISOString().split('T')[0];
}
