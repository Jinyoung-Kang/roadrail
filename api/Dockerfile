# syntax=docker/dockerfile:1.7
# Spring Boot 4.1 (Java 21) — 빌드 컨텍스트는 저장소 루트 (db/migrations 를 이미지에 함께 넣기 위해)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY api/gradlew api/settings.gradle.kts api/build.gradle.kts ./
COPY api/gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null
COPY api/src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/* \
 && groupadd -r app && useradd -r -g app app
COPY --from=build /src/build/libs/roadrail-api.jar /app/app.jar
COPY db/migrations /app/db/migrations
ENV TZ=Asia/Seoul
USER app
EXPOSE 8300
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]
