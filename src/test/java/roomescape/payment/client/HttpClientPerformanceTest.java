package roomescape.payment.client;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HttpClientPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(HttpClientPerformanceTest.class);

    @Autowired
    private PaymentRestClientConfiguration paymentConfig;

    // 서버 응답이 좀 더 걸리는 URL 사용
    private static final String TEST_URL = "https://httpbin.org/delay/0.2"; // 0.2초 지연

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

    private RestClient createDefaultClient() {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()))
                .build();
    }

    private RestClient createOptimizedClient() {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(paymentConfig.httpClient()))
                .build();
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
}
