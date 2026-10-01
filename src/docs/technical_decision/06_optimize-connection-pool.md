google에 접속했을 때, 요청 처리가 끝나기까지의 전체 시간은 다음과 같다.

![](https://velog.velcdn.com/images/jminkkk/post/9c8ab4d5-e251-4947-8f25-09d8f01c1b55/image.png)

여기서 주목할 점은 **Initial Connection + SSL** 즉, **TCP 핸드셰이크와 TLS 핸드셰이크**에 소요되는 시간이,

서버가 실제 요청을 처리하는 **Waiting for Server Response** 시간만큼이나 크다는 것이다.

즉, 외부 API 연동에서 성능 최적화의 핵심은 HTTP 커넥션 재사용이다.

매번 새로운 TCP/TLS 세션을 만들면 다음 비용이 발생한다.

1. TCP 3-way 핸드셰이크
2. TLS 핸드셰이크

이 과정을 매 요청마다 반복하면 **응답 지연이 크게 증가**한다. 반대로, **이미 연결된 세션을 재활용**하면 초기 연결 시간을 아낄 수 있어 전체 응답 속도가 빨라진다.

스프링(Spring)과 Apache HttpClient를 기반으로, 어떻게 커넥션 풀을 최적화할 수 있는지 살펴보고 주요 설정들을 적용해보자. 기존 글에 이어서 토스 API를 기준으로 설정한다.

## 연동 서버와 통신 방식 파악하기

토스 결제 승인 API 응답 예시는 다음과 같다.

```java
POST <https://api.tosspayments.com/v1/payments/confirm>

HTTP/2 404 Not Found // Connection: keep-alive 헤더가 없어도, HTTP/2는 기본적으로 keep-alive & 멀티플렉싱 적용
content-type: application/json
// 생략
strict-transport-security: max-age=31536000 // TLS 연결 장기간 유지 가능
x-http2-stream-id: 3 // 요청에 부여되는 id

```

주요 확인할 부분은 다음과 같다.

1. **HTTP/2 사용**
  - HTTP/1.1에서 명시적으로 요청해야 하지만, HTTP/2는 명세상 기본이기 때문에 `Connection: keep-alive` 헤더가 없어도 기본적으로 커넥션 유지한다.
  - 하나의 TCP + TLS 세션에서 여러 요청을 멀티플렉싱한다.
2. x-http2-stream-id: 3
  - 최소 2개의 요청이 같은 TCP 커넥션에서 처리됨을 의미한다.
3. **Strict-Transport-Security**
  - `max-age=31536000` → TLS 연결이 장기간 유지 가능하다는 것을 알 수 있다.

즉, HTTP/2 환경에서는 커넥션 재사용과 멀티플렉싱이 기본적이다.

## 커넥션 풀 최적화 전략

커넥션 재사용과 커넥션 풀링은 밀접한 관련이 있지만 완전히 같은 개념은 아니다.

### **커넥션 재사용 vs 커넥션 풀링**

- **커넥션 재사용**: 기존 TCP/TLS 세션을 계속 활용하는 것
- **커넥션 풀링**: 멀티스레드 환경에서 여러 요청이 동시에 안정적으로 재사용될 수 있도록 관리하는 것

다시 말해 http 2.0가 기본적으로 커넥션을 재사용한다고 할지라도 멀티 쓰레드 환경에서 **동시에 여러 결제 요청**이 들어오므로, 커넥션 재사용을 위해서는 커넥션 풀링이 반드시 필요하다고 생각하였다.

```java
@Bean
public RestClient.Builder restClient() {
    return RestClient.builder()
            .requestFactory(new SimpleClientHttpRequestFactory());
}

```

기존 구현 코드에서는 스프링에서 제공하는 `SimpleClientHttpRequestFactory`를 통해 RestClient를 생성했었는데, `SimpleClientHttpRequestFactory`는 커넥션 풀을 지원하지 않는다.

**Apache HttpClient + PoolingHttpClientConnectionManager**를 사용하면,

Idle 커넥션 관리, LIFO/STRICT 모드, Idle Eviction, AIMDBackoffManager 등 다양한 최적화가 가능하다.

## 다양한 설정으로 풀링 최적화하기

### 1. LIFO 정책으로 handshaking 최소화하기

커넥션 풀에서 어떤 커넥션을 먼저 재사용할지 결정하는 정책이다. 옵션은 다음과 같다.

- **LIFO**
  - 가장 최근에 사용한 커넥션을 우선적으로 재사용
  - TCP 연결이 살아있을 확률이 높아 효율적
- **FIFO**
  - 오래된 커넥션부터 사용
  - 세션이 만료됐을 가능성이 비교적 높다
  - 따라서 핸드셰이크 다시 수행할 확률이 높다

이때는 TCP뿐만 아니라 TLS handshake에 대해 고려를 했다. TLS는 세션 재활용 기능이 있기 때문에 최근 커넥션을 재사용할 경우 full handshake를 줄일 수 있다.

즉, LIFO는 핫한 커넥션을 계속 재사용해서 TLS 핸드셰이크 비용을 줄이는 데 유리하다.

핫한 커넥션만 사용하기 때문에 오래된 커넥션이 지속해서 사용되지 않을 가능성이 있지만, 해당 내용은 TTL 설정 등 다른 설정값으로 보완 가능하다고 판단하였다.

```java
PoolingHttpClientConnectionManager connectionManager =
        PoolingHttpClientConnectionManagerBuilder.create()
                // 생략
                .setConnPoolPolicy(PoolReusePolicy.LIFO)  // 커넥션 재사용 LIFO
                .build();

```

### 2. STRICT 동시성 정책으로 안정성 확보

다음 알아볼 옵션은 다수 스레드가 동시에 커넥션 풀을 사용할 때 충돌을 막는 정책이다.

커넥션 풀은 요청 스레드들이 동시에 커넥션을 빌려가는 구조인데, 멀티스레드 환경에서 커넥션 경쟁을 제어하기 위해 `PoolConcurrencyPolicy`를 활용한다.

- **STRICT**
  - 스레드별 대기 큐를 공평하게 관리
  - 한 스레드가 오래 기다리면 먼저 커넥션을 받음
  - 커넥션을 "엄격하게 점유"해서 예측 가능하기 때문에 안정성이 비교적 높고, Latency가 일정함
- **LENIENT**
  - 그냥 가능한 커넥션을 먼저 가져감
  - 스레드가 운 좋으면 바로 가져가고, 아니면 계속 기다림
  - 성능은 비교적 낫지만, 하지만 특정 스레드가 굶을 수도 있음

**STRICT** 정책은 커넥션 경합 상태를 엄격히 관리하여 동시성 문제를 방지한다.

덕분에 안정적으로 커넥션이 할당되고, 예기치 않은 오류 없이 안정적인 요청 처리가 가능하다. 따라서 성능보다는 안정성에 초점을 두어 STRICT 정책을 할당하였다.

```java
PoolingHttpClientConnectionManager connectionManager =
        PoolingHttpClientConnectionManagerBuilder.create()
                // 생략
                .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT) //
                .build();

```

### 3. Idle Eviction과 TTL로 오래된 커넥션 제거

유휴 커넥션(idle connection)은 ‘커넥션 풀에 반납된 상태로 현재 사용 중이 아닌 커넥션’을 말한다.

오래된 커넥션은 서버 쪽에서 커넥션을 강제로 닫았거나, 네트워크 문제가 생겨서 해당 커넥션이 더 이상 유효하지 않을 수 있다. 즉, 사용 불가능한 상태로 커넥션이 계속 유지되면서 시스템 자원(메모리, 네트워크 소켓 등)을 불필요하게 점유한다는 것이다.

이 상태를 막고 안전하게 재사용할 수 있는 커넥션만 남기기 위해 다음의 설정을 할 수 있다.

1. 유휴 커넥션이 오래되면 유효성 검사를 해서 문제가 있으면 폐기하거나
2. TTL(Time To Live)을 설정해 일정 시간이 지나면 커넥션을 새로 교체
- **Idle Eviction**
  - 유휴 상태(사용되지 않는 상태)인 커넥션을 일정 시간 후에 풀에서 제거합니다.
  - 일정 기간 활동이 없으면 커넥션 유효성을 체크하거나 제거합니다.
- **TTL (Time To Live)**
  - 커넥션 생성 후 일정 시간이 지나면 강제로 커넥션을 종료시켜 재생성하게 합니다.
- **ValidateAfterInactivity**
  - 풀에서 커넥션을 재사용하기 전에 유효성 검사를 수행할지 여부를 결정하는 기준 시간이다.
  - 특정 시간 이상 유휴 상태(idle)였던 커넥션은 재사용 전에 서버와의 연결이 살아있는지 확인한다**.**

```java
    private ConnectionConfig connectionConfig() {
        return ConnectionConfig.custom()
                .setTimeToLive(TimeValue.ofMinutes(1))
                .setValidateAfterInactivity(TimeValue.ofMinutes(1))
                .build();
    }

```

### 4. AIMDBackoffManager: 동적 커넥션 조절로 과부하 방지

스프링은 커넥션 풀을 사용하는 경우, 3가지의 BackOffManager를 제공한다.

백오프 전략은 해당 호스트와의 최대 커넥션 풀의 크기를 조정하는 전략으로, 크게 세 가지 유형이다.

- 고정된 크기로 활성 커넥션을 증감하는 LinearBackOff
- 지수적으로 증가 후 장애 발생 시 지수적으로 감소하며 연결을 관리하는 ExponentialBackoff
- 점진적으로 증가하고 장애 발생 시 급격하게 감소하는 AIMDBackoff

위 3가지에 대해 특징을 간략하게 표로 정리하자면 다음과 같다.

여기서 AIMD(합 증가 곱 감소)는 TCP 혼잡 제어에서 사용되는 개념으로, 혼잡이 없을 때는 천천히 증가, 혼잡이 감지되면 빠르게 줄이는 방식이다.

![](https://velog.velcdn.com/images/jminkkk/post/de1db93e-29c7-4be6-b759-0eee64895050/image.png)

연동 서버가 결제 서버이기 때문에 어느정도 예측 가능한 수준으로 증가하는 것이 안정적이라고 판단했다.

동시에 토스 페이먼츠는 비교적 안정적일 것이라 예상해 장애가 발생할 경우 빠르게 대응할 수 있는 AIMD(합증가 곱감소) 전략을 적용하기로 했다.

장애 발생 시 커넥션 최대 수를 50% 줄이고, 이후 정상 응답 시 1개씩 증가하도록 하였다.

```java
    public HttpClient httpClient() {
        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig())
                .setConnectionManager(connectionManager())
                // AIMDBackoffManager 설정 추가 - 혼잡 발생 시
                .setBackoffManager(new AIMDBackoffManager(connectionManager()))
                .setRetryStrategy(IdempotencyKeyRetryStrategy.INSTANCE)
                .build();
    }

```

## 성능 테스트를 통해 비교하기

해당 설정들이 실제로 유효한지 확인하기 위해 성능 테스트를 진행했다.
실제 토스 API에 부하를 주는 것보다는 안전하게 https://httpbin.org/ 서비스를 타겟으로 하여 실제 요청처럼 0.2초의 딜레이를 주어 요청 성능을 비교했다.

```java
@Test
void compareSequentialRequests() {
    log.info("=== 순차 요청 비교 ===");

    RestClient defaultClient = createDefaultClient();
    RestClient optimizedClient = createOptimizedClient();

    long defaultTime = timeSequentialRequests(defaultClient, "기본 설정", 20);
    long optimizedTime = timeSequentialRequests(optimizedClient, "최적화 설정", 20);

    double improvement = ((double)(defaultTime - optimizedTime) / defaultTime) * 100;
    log.info("순차 요청 개선율: {}%", improvement);
}

    private long timeSequentialRequests(RestClient client, String name, int count) {
        Instant start = Instant.now();
        int success = 0;

        for (int i = 0; i < count; i++) {
            try {
                client.post().uri(TEST_URL).body("{}").retrieve().toEntity(String.class);
                success++;
            } catch (Exception e) {
                log.debug("요청 실패: {}", e.getMessage());
            }
        }

        long time = Duration.between(start, Instant.now()).toMillis();
        log.info("{} - {}회 순차: {}ms, 성공: {}", name, count, time, success);
        return time;
    }

```

![](https://velog.velcdn.com/images/jminkkk/post/9ad4a8ad-f4b9-4df0-bd1d-ef51c66081ef/image.png)

최적화 후가 전에 비해 14.134% 즉 약 1.16배 빨라진 것을 알 수 있다.

```java
    @Test
    void compareConcurrentRequests() {
        log.info("=== 동시 요청 비교  ===");

        RestClient defaultClient = createDefaultClient();
        RestClient optimizedClient = createOptimizedClient();

        long defaultTime = timeConcurrentRequests(defaultClient, "기본 설정", 100);
        long optimizedTime = timeConcurrentRequests(optimizedClient, "최적화 설정", 100);

        double improvement = ((double)(defaultTime - optimizedTime) / defaultTime) * 100;
        log.info("동시 요청 개선율: {}%", improvement);
    }

    private long timeConcurrentRequests(RestClient client, String name, int count) {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        Instant start = Instant.now();

        CompletableFuture<?>[] futures = new CompletableFuture[count];
        for (int i = 0; i < count; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    client.post().uri(TEST_URL).body("{}").retrieve().toEntity(String.class);
                } catch (Exception e) {
                    // 무시
                }
            }, executor);
        }

        try {
            CompletableFuture.allOf(futures).get();
        } catch (Exception e) {
            log.warn("일부 요청 실패");
        }

        long time = Duration.between(start, Instant.now()).toMillis();
        executor.shutdown();

        log.info("{} - {}회 동시: {}ms", name, count, time);
        return time;
    }

```

![](https://velog.velcdn.com/images/jminkkk/post/820a0eb1-d69e-4d0a-a30a-bcdc19d62cab/image.png)

특히 동시 요청의 경우, 커넥션 풀링의 효과로 20.8초 → 8.4초로 2.5배 이상 감소한 것을 확인할 수 있다.

---
## 결론

Apache HttpClient와 PoolingHttpClientConnectionManager를 적용하여

- 불필요한 TLS 핸드셰이크 최소화 (LIFO 정책)
- 안정적인 동시성 제어 (STRICT 정책)
- 자동화된 커넥션 생명주기 관리 (TTL, Idle Eviction)
- 트래픽 상황별 동적 풀 크기 조절 (AIMD BackoffManager)

하여 동시 요청에서 2.5배 성능 향상를 이룰 수 있었다.

단순히 SimpleClientHttpRequestFactory를 사용하는 것보다 응답 속도가 크게 빨라지고, 장애 상황에서도 훨씬 안정적으로 대응할 수 있다.

---
