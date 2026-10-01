앞선 글에서 멱등키 헤더를 통해 재시도 요청에도 중복 결제를 막는등 멱등성을 보장하는 처리를 해보았다.

하지만 아무리 멱등하다하더라도 무한대로 재시도 하는 것이 마냥 좋은 것은 아니다.

## 연동 서버 장애가 지연되는 경우를 고려해보자

### **Retry Storm antipattern(재시도 폭풍 안티패턴)**

1. 만약 연동 서버가 다운된 상태이라면, 장애 상황에서 빠른 재시도는 성공 가능성이 낮다.
2. **오히려** 단시간 내에 재시도하면 서비스 복구를 방해하는 요인이 될 수도 있다.
3. 우리 서버와 연동 서버의 커넥션이 많다면, 스레드, 커넥션 풀 고갈 등 우리 서버의 리소스도 낭비된다.

우리 서버 입장에서는 재시도를 통해 성공 가능성을 높힐 수 있지만, 연동 서버 입장에서는 더 큰 부하를 얻게 될 수 있다.

이처럼 장애 상황에서 짧은 간격의 무분별한 재시도는 “Retry Storm” 이라고 불리며, 대표적인 안티패턴 중 하나이다.

장애가 길어질 경우, 아예 해당 PG사를 사용 불가 처리하여 **Fast Fail** 하는 것이 좋다. 이는 리소스를 절약할 뿐만 아니라, 사용자가 무제한으로 재시도하는 상황도 방지한다.

## 연동 서버의 장애 지속을 차단해보자

서킷 브레이커는 시스템 간 연동 시 장애 전파를 막기 위한 회로 차단기 패턴으로, 자주 사용되는 장애 대응 전략 중 하나이다. 주 목적은 다음과 같다.

- 지속적인 실패로 인한 시스템 자원 낭비를 방지
- 장애가 발생한 연동 서버에 무분별하게 트래픽이 몰리는 것을 차단
- 장애 복구까지의 시간을 확보하여 안정적인 복구 유도

### 서킷 브레이커 설계

기본적으로 서킷 브레이커는 3가지 상태를 가진다.

![image.png](attachment:55cb00bf-8358-4b10-b7e9-9f461d9c0b0b:image.png)

1. **Closed (닫힘)**
  - 모든 요청이 정상적으로 전달된다.
  - 실패율이 특정 임계치를 초과하면 Open 상태로 전환된다.
  - 특정 PG와 연동 시 장애가 지연될 경우, 클라이언트는 아예 사용자에게 일시적으로 PG 노출을 막는 방안을 설계했다.
2. **Open (열림)**
  - 더 이상 요청을 연동 서버에 전달하지 않고 바로 실패(Fail Fast) 처리한다.
  - 일정 시간 이후 Half-Open 상태로 전환된다.
3. **Half-Open (반열림)**
  - 일부 요청만 연동 서버로 전달하여 정상 복구 여부를 테스트한다.
  - 성공률이 높아지면 Closed 상태로 전환되고, 실패가 계속되면 다시 Open 상태로 전환된다.

그런데, 만약 닫힘 상태일 경우 사용자는 무조건 실패 응답을 받는다.

이 경우 애초에 사용자에게 해당 PG사를 노출시키지 않음으로써 불필요한 네트워크 통신을 줄이고

재시도 해야 하는 사용자의 피로감을 낮출 수 있다.

따라서 특정 PG와 연동 시 장애가 지연될 경우, 아예 사용자에게 일시적으로 PG 노출을 막는 방안을 설계했다.

![image.png](attachment:40caf5a8-1b62-47ea-a94d-ca8d617cd114:image.png)

# resilience4j와 함께 서킷 브레이커 구현하기

## 1. Circuit Breaker 구성 파일 작성하기

resilience4j은 Java 전용으로 개발된 경량 라이브러리로 서킷 브레이커 기능을 제공한다.

주요 설정은 다음과 같다.

- failureRateThreshold:
  - 서킷을 열 실패율 임계치
  - 예 - 50%로 설정하면 실패율이 50%를 넘을 때 서킷이 열립니다.
- waitDurationInOpenState
  - OPEN 상태에서 HALF_OPEN으로 전환되기까지의 대기 시간
- slidingWindowSize
  - 실패율 계산에 사용할 최근 호출 수
  - 시간 기반 또는 카운트 기반으로 설정 가능
- minimumNumberOfCalls
  - 실패율 계산을 위한 최소 호출 수
  - 이 수치 이하에서는 서킷이 열리지 않음
- permittedNumberOfCallsInHalfOpenState
  - HALF_OPEN 상태에서 허용할 호출 수

![https://meetup.nhncloud.com/posts/385](attachment:99d472d4-6828-400b-bbab-203c3c43935b:image.png)

https://meetup.nhncloud.com/posts/385

resilience4j 사용에 있어 또 하나 알아야 할 것은 실패율 계산 방식이다. 슬라이딩 윈도우 방식으로 카운트 기반, 시간 기반의 윈도우가 있다.

- **카운트 기반 윈도우**: 최근 N개의 호출을 기준으로 실패율을 계산
  - 예를 들어 윈도우 크기가 10이면 최근 10번의 호출 중 실패 비율 계산
- **시간 기반 윈도우:** 최근 N초 동안의 호출을 기준으로 실패율을 계산
  - 60초 윈도우라면 지난 60초간의 모든 호출을 대상으로 실패율을 계산

특히, 윈도우가 가득 차기 전까지는 `minimumNumberOfCalls` 설정값 이상의 호출이 있어야 실패율 계산이 시작되는 점을 주의해야한다.

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        registerHealthIndicator: true
        slidingWindowSize: 20
        minimumNumberOfCalls: 10
        permittedNumberOfCallsInHalfOpenState: 5
        automaticTransitionFromOpenToHalfOpenEnabled: true
        waitDurationInOpenState: 30s
        failureRateThreshold: 60
```

## 2. **결제 에러의 의미 기반 분류**

서킷 브레이커가 제대로 동작하려면 어떤 예외가 실제 장애 상황이고, 어떤 예외가 단순한 비즈니스 로직 오류인지 구분해야 한다. 예를 들어 소켓 타임아웃이나 서버 오류(HTTP 5xx)는 연동 서버의 장애일 가능성이 높다. 이 경우는 재시도하거나 서킷 브레이커를 통해 빠르게 실패시키는 것이 적절하다.

반면, 사용자가 잘못 입력한 요청(예: 유효하지 않은 카드번호)이거나 개발자의 설정 오류(예: 잘못된 시크릿 키)는 장애 상황이 아니기 때문에 서킷 브레이커에 포함되면 안 된다.

또한 500 에러가 아니더라도 연동 서버 내부의 상태 오류나 일시적인 비정상 응답도 장애로 취급될 수 있다. 그래서 실제 결제 서버(PG)와 연동할 때는 **API 응답에 대한 에러 타입 분류 체계**를 명확히 세우는 것이 중요하다.

외부 결제 서버에서 발생할 수 있는 오류는 다양하다. 그중 일부는 네트워크 문제이거나 토스 서버 자체의 장애이고, 일부는 사용자 카드 문제이거나 정책 위반이다. 우리는 이 오류들을 다음 기준으로 분류했다.

### 결제 서버 연동 시 에러 타입 정의

외부 결제 서버에서 발생할 수 있는 오류는 다양하다. 그중 일부는 네트워크 문제이거나 토스 서버 자체의 장애이고, 일부는 사용자 카드 문제이거나 정책 위반이다. 이 오류들을 다음 기준으로 분류했다.

- `retryable`: 해당 오류가 재시도하면 해결될 수 있는가?
- `circuitBreakerFailure`: 서킷 브레이커에서 실패로 기록할 대상인가?

```java
public enum PaymentErrorType {
    INFRASTRUCTURE(true, true, "네트워크/인프라 오류"), // 서킷브레이커 대상, 재시도 가능
    PROVIDER_ERROR(true, true, "PG사 장애 또는 내부 시스템 오류"), // 서킷브레이커 대상, 재시도 가능
    CLIENT_ERROR(false, false, "API 요청/구성 오류"), // 재시도 불가, 개발자 책임
    USER_INPUT_ERROR(false, false, "사용자 카드/계좌 문제"), // 재시도 불가, 사용자 조치 필요
    POLICY_VIOLATION(false, false, "한도/정책 위반"), // 재시도 불가, 사용자 조치
    STATE_TEMPORARY(false, true, "일시적 상태 불일치"),// 재시도 가능 (예: 승인 대기)
    STATE_FINAL(false, false, "영구 상태 오류"),      // 재시도 불가 (예: 이미 처리됨)

    private final boolean retryable;
    private final boolean circuitBreakerFailure;
    private final String description;

		// 생략
}
```

예를 들어 INFRASTRUCTURE, PROVIDER_ERROR는 대부분 네트워크 단이나 PG사 장애로 인한 것으로, 장애 상황이며 재시도가 필요하다. 반면 USER_INPUT_ERROR, POLICY_VIOLATION은 사용자가 해결해야 하므로 재시도도, 장애 처리도 의미 없다.

그 후, 이 분류를 기반으로 `PaymentException`을 상속하는 예외 클래스를 만들었고, 이를 Resilience4j의 `recordExceptions`와 `ignoreExceptions`에 설정했다:

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
      // 생략
        recordExceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
          - java.net.ConnectException
          - java.net.SocketTimeoutException
          - org.springframework.web.client.ResourceAccessException
          - roomescape.payment.client.exception.PaymentInfrastructureException
          - roomescape.payment.client.exception.PaymentProviderException
        ignoreExceptions:
          - roomescape.payment.client.exception.PaymentBusinessException
          - roomescape.payment.client.exception.PaymentClientException
          - roomescape.payment.client.exception.PaymentPolicyException
          - roomescape.payment.client.exception.PaymentStateException

```

### 토스 에러 코드 매핑하기

이렇게 하면 **정말 장애로 판단되는 예외만 서킷 브레이커에 영향을 주게 되고**, 비즈니스 로직상 실패는 무시된다. 토스의 결제 API와 연동하고 있었고, 다양한 실패 응답을 enum으로 정리해 `PaymentErrorType`에 매핑했다.

```java
public enum TossErrorCode {

	INVALID_CARD_NUMBER(PaymentErrorType.USER_INPUT_ERROR, "카드번호를 다시 확인해주세요."),
	REJECT_ACCOUNT_PAYMENT(PaymentErrorType.USER_INPUT_ERROR, "잔액부족으로 결제에 실패했습니다."),
	FAILED_INTERNAL_SYSTEM_PROCESSING(PaymentErrorType.PROVIDER_ERROR, "내부 시스템 처리 작업이 실패했습니다."),
	UNAPPROVED_ORDER_ID(PaymentErrorType.STATE_TEMPORARY, "아직 승인되지 않은 주문번호입니다."),
	ALREADY_PROCESSED_PAYMENT(PaymentErrorType.STATE_FINAL, "이미 처리된 결제입니다."),
	// 생략
}
```

### 열림, 닫힘 상태 테스트하기

이제 PG사에서 제공하는 실패 헤더를 통해 에러를 임의로 발생 시켜 실제로 반오픈, 오픈 상태로 변화되는지 확인해보면, 처음에는 다음과 같다.

```yaml
# 에러 발생 전
{
  "circuitBreakers": {
    "default-pg": {
      "failureRate": "-1.0%",
      "slowCallRate": "-1.0%",
      "failureRateThreshold": "60.0%",
      "slowCallRateThreshold": "100.0%",
      "bufferedCalls": 0,
      "failedCalls": 0,
      "slowCalls": 0,
      "slowFailedCalls": 0,
      "notPermittedCalls": 0,
      "state": "CLOSED"
    },
    "toss-payment": {
      "failureRate": "-1.0%",
      "slowCallRate": "-1.0%",
      "failureRateThreshold": "80.0%",
      "slowCallRateThreshold": "100.0%",
      "bufferedCalls": 0,
      "failedCalls": 0,
      "slowCalls": 0,
      "slowFailedCalls": 0,
      "notPermittedCalls": 0,
      "state": "CLOSED"
    }
  }
}
```

하지만 에러 응답을 반환할 경우 state는 다음과 같이 CLOSED 상태로 변경되는 것을 알 수 있다.

```yaml
# 에러 발생 후 
{
  "circuitBreakers": {
    "default-pg": {
      "failureRate": "-1.0%",
      "slowCallRate": "-1.0%",
      "failureRateThreshold": "60.0%",
      "slowCallRateThreshold": "100.0%",
      "bufferedCalls": 0,
      "failedCalls": 0,
      "slowCalls": 0,
      "slowFailedCalls": 0,
      "notPermittedCalls": 0,
      "state": "CLOSED"
    },
    "toss-payment": {
      "failureRate": "100.0%",
      "slowCallRate": "0.0%",
      "failureRateThreshold": "60.0%",
      "slowCallRateThreshold": "100.0%",
      "bufferedCalls": 10,
      "failedCalls": 10,
      "slowCalls": 0,
      "slowFailedCalls": 0,
      "notPermittedCalls": 2,
      "state": "OPEN" # OPEN 상태로 전환
    }
  }
}
```

## **3.** 프론트 클라이언트에게 PG 상태 알리기

결제 시스템에서 PG사(Payment Gateway)의 장애 여부는 **사용자 경험에 큰 영향을 미친다.**

예를 들어 카드 결제를 시도했는데 내부적으로 PG 장애로 실패했다면, 사용자는 **"왜 실패했는지"를 알기 어렵고, 반복해서 시도하게 된다.** 일반적인 구현에서는 이런 정보를 프론트엔드에서 직접 확인할 수 없다.

따라서 **클라이언트가 결제창을 구성하기 위해 서버에서 PG 목록을 요청할 때**, 서버가 resilience4j 서킷 브레이커 상태를 기반으로 사용 가능한 PG만 응답하는 구조가 가장 효율적이다.

프론트가 PG 상태를 알 수 있도록 서버 측에서 알림이 필요한데, 구현 방식으로는 다음과 같이 고려했다.

- 옵션 A: 클라이언트가 주기적으로 상태 확인 요청을 보낸다. (polling)
- 옵션 B: 서버에서 PG 장애가 감지되면 실시간으로 클라이언트측에 브로드캐스팅한다. (SSE 같은 push)
- 옵션 C: 클라이언트가 결제 페이지를 조회할 때, 연동 가능한 PG사를 먼저 조회한다.

### Polling 방식

프론트가 주기적으로 `/client-status`를 요청하는 방식이다.

```jsx
// 프론트에서 5초마다 상태를 확인
useEffect(() => {
  const interval = setInterval(() => {
    fetch('/payment/client-status')
      .then((res) => res.json())
      .then((status) => setPgStatus(status));
  }, 5000);

  return () => clearInterval(interval);
}, []);
```

실시간성은 주기 간격에 따라 제한되고, 트래픽이 많아질수록 서버 부하 발생할 수 있다.

### SSE, WebSocket 등 브로드캐스팅 방식

상태 변경 시 서버가 클라이언트에게 즉시 푸시하는 방식이다.

<aside>
📢

브로드캐스팅 구현 방식으로는 크게 SSE(Server Sent Event)와 WebSocket 를 고려할 수 있다.

두 방식의 차이점은 웹소켓은 클라이언트와 서버가 서로 요청을 보낼 수 있는 양방향이지만, SSE는 서버만 요청을 보낼 수 있는 단방향 요청인 것이다.

현재 요구사항에서는 단방향으로 상태만 전달하면 되기 때문에, SSE 예제로 확인해보자.

</aside>

상태가 변경되는 즉시 이벤트를 보내기 때문에 반영이 빠르고 Polling과 달리 불필요한 요청이 없다.

```java
@GetMapping(path = "/pg-status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<Map<PaymentClientType, PaymentClientStatus>>> streamPgStatus() {
    return paymentService.getPgStatusFlux()
            .map(status -> ServerSentEvent.builder(status).build());
}
```

### PG사의 상태를 확인하는 추가 API 제공

결제 페이지를 조회하기 직전에만 상태 확인하는 방식이다. 가장 단순하면서도 불필요한 요청을 최소화할 수 있다.

```java
@RestController
@RequestMapping("/payment")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(final PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/client-status")
    public ResponseEntity<Map<PaymentClientType, PaymentClientStatus>> getPaymentProvidersStatus() {
        Map<PaymentClientType, PaymentClientStatus> paymentClientStatuses = paymentService.getPaymentClientStatuses();
        return ResponseEntity.ok(paymentClientStatuses);
    }
}

// Service Layer
    private boolean isClientAvailable(PaymentClient client) {
        try {
            PaymentClientType paymentProvider = client.getPaymentProvider();
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(paymentProvider.name().toLowerCase() + "-payment");
            return circuitBreaker.getState() == CircuitBreaker.State.CLOSED;
        } catch (Exception e) {
            return true; // 조회 실패시 사용 가능한 것으로 간주
        }
    }

```

실제 UX 플로우는 결제 PG 선택 화면은 **예약 화면**에 노출됩니다.

사용자가 예약을 결정하고 결제 단계까지 오는 비율은 높지 않다고 판단하였고 즉, **실제 호출 빈도는 낮습니다.**

반대로 **브로드캐스팅(WebSocket/SSE)** 방식은 요청량은 비교적 적지만, **프론트와 세션을 지속적으로 유지해야 하는 부담**이 있습니다.

결제 상태 화면은 상시 유지되는 화면이 아니기 때문에, 세션을 유지하면서까지 실시간 반영을 할 필요는 없다고 판단했습니다.

대부분의 사용자는 방탈출 예약을 위해 결제 페이지 조회를 조회하는데, 결제 페이지 자체는 1회로 조회 가능하며 장애 발생 빈도도 높지 않다. 이런 상황에서 서버가 모든 클라이언트에 대해 **지속적으로 상태를 유지하며 PG 상태를 push하거나 주기적으로 polling을 하는 것은 과한 리소스 낭비**가 될 수 있다.

따라서 옵션 C(페이지 조회 시 상태 조회)를 선택했다.

이 방식은 서버와 클라이언트 모두에게 단순하면서도 안정적인 구조를 제공한다. 여기에 더해 **사용자 입장에서 장애를 감지하고 피드백을 받는 방식**도 자연스럽게 향상된다.

- PG가 비활성화되어 있으면 결제창 자체에서 숨김 또는 비활성화 처리
- 다른 PG 수단을 유도하여 결제 전환율을 유지

![image.png](attachment:8dd94aa0-809d-432e-b7e2-03e77b78464e:image.png)

### 테스트 코드

서킷 브레이커의 상태에 따라 PG 사의 상태도 변함을 테스트 코드로 작성해보면 다음과 같다.

1. 서킷 브레이커가 CLOSED 상태일 때 사용 가능한 상태를 반환한다

```java
    @Test
    @DisplayName("서킷 브레이커가 CLOSED 상태일 때 사용 가능한 상태를 반환한다")
    void getPaymentClientStatuses_WhenCircuitBreakerClosed_ShouldReturnAvailable() {
        // given
        when(circuitBreakerRegistry.circuitBreaker("toss-payment-payment"))
            .thenReturn(tossCircuitBreaker);
        when(circuitBreakerRegistry.circuitBreaker("default_pg-payment"))
            .thenReturn(defaultCircuitBreaker);
        
        when(tossCircuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        when(defaultCircuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        
        // when
        Map<PaymentClientType, PaymentClientStatus> result = paymentService.getPaymentClientStatuses();
        
        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(PaymentClientType.TOSS_PAYMENT).available()).isTrue();
        assertThat(result.get(PaymentClientType.DEFAULT_PG).available()).isTrue();
    }
```

1. 서킷 브레이커가 OPEN 상태일 때 사용 불가능한 상태를 반환한다

```java
    @Test
    @DisplayName("서킷 브레이커가 OPEN 상태일 때 사용 불가능한 상태를 반환한다")
    void getPaymentClientStatuses_WhenCircuitBreakerOpen_ShouldReturnUnavailable() {
        // given
        when(circuitBreakerRegistry.circuitBreaker("toss-payment-payment"))
            .thenReturn(tossCircuitBreaker);
        when(tossCircuitBreaker.getState()).thenReturn(CircuitBreaker.State.OPEN);
        
        // when
        Map<PaymentClientType, PaymentClientStatus> result = paymentService.getPaymentClientStatuses();
        
        // then
        assertThat(result.get(PaymentClientType.TOSS_PAYMENT).available()).isFalse();
        assertThat(result.get(PaymentClientType.TOSS_PAYMENT).status()).isEqualTo("UNAVAILABLE");
    }
```

![image.png](attachment:829b19b3-97ec-4376-8f4e-548421e4ebb3:image.png)

- 최종적으로 적용한 설정

    ```java
    public HttpClient httpClient() {
        return HttpClients.custom()
                .setConnectionBackoffStrategy(new DefaultBackoffStrategy())
                .setRetryStrategy(DefaultHttpRequestRetryStrategy.INSTANCE)
                .setDefaultRequestConfig(requestConfig())
                .setBackoffManager(new AIMDBackoffManager(connectionManager()))
                .build()
    }
    
    private RequestConfig requestConfig() {
        return RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(1))
                .setResponseTimeout(Timeout.ofMinutes(1))
                .build();
    }
    
    private PoolingHttpClientConnectionManager connectionManager() {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(20)
                .setDefaultConnectionConfig(connectionConfig())
                .setDefaultSocketConfig(socketConfig())
                .build();
    }
    
    private ConnectionConfig connectionConfig() {
        return ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(1))
                .setSocketTimeout(Timeout.ofMinutes(2))
                .build();
    }
    
    private SocketConfig socketConfig() {
        return SocketConfig.custom()
                .setSoTimeout(Timeout.ofMinutes(2))
                .build();
    }
    ```

  `apache.http` 의 `HttpClient`는 재시도 전략 등을 설정할 수 있는데, 제공되는 `DefaultHttpRequestRetryStrategy` 는 Timeout 예외들의 부모 클래스 InterruptedIOException를 포함하여 여러 호출에 실패한 경우의 예외들에 대해 재시도를 시도한다.


---
