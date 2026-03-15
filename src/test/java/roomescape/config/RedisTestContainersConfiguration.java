package roomescape.config;

import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@TestConfiguration
public class RedisTestContainersConfiguration {
    private static final int REDIS_PORT = 6379;

    private static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>("redis:7.4.1-alpine3.20")
                    .withExposedPorts(REDIS_PORT)
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofSeconds(60));

    static {
        REDIS_CONTAINER.start();
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(
                REDIS_CONTAINER.getHost(),
                REDIS_CONTAINER.getMappedPort(REDIS_PORT)
        );
    }
}
