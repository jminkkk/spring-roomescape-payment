# ── Stage 1: Build ────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle* .

# 의존성만 먼저 다운로드 (레이어 캐시 활용)
RUN ./gradlew dependencies --no-daemon -q || true

COPY src src

# asciidoctor는 test에 의존하므로, bootJar만 빌드하고 test/docs는 skip
RUN ./gradlew bootJar -x test -x asciidoctor --no-daemon

# ── Stage 2: Run ──────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]