package com.roadrail;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * QA-02: DB 가 응답을 멈추면(일시 정지 · 네트워크 단절) 진행 중인 조회가 끝없이 기다렸다 — 헬스 검사도 40초 넘게 응답하지 않음.
 * 다른 시험과 컨테이너를 나눠 쓰지 않도록 이 시험만의 PostgreSQL 을 띄워 멈춘다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DbStallIT {
    static final PostgreSQLContainer PG = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        PG.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.flyway.locations", () -> "filesystem:" + Path.of(System.getProperty("migrations.dir", "../db/migrations")).toAbsolutePath());
        r.add("roadrail.kakao-rest-api-key", () -> "");
    }

    @Autowired
    MockMvc mvc;

    @AfterAll
    static void resume() {
        try { DockerClientFactory.instance().client().unpauseContainerCmd(PG.getContainerId()).exec(); } catch (RuntimeException ignored) { }
    }

    @Test
    void qa02_healthAnswersDownWithinSecondsWhenTheDatabaseStopsResponding() throws Exception {
        assertThat(mvc.perform(get("/api/v1/health")).andReturn().getResponse().getStatus()).isEqualTo(200);  // 풀의 연결을 데워 둔다
        DockerClientFactory.instance().client().pauseContainerCmd(PG.getContainerId()).exec();
        try {
            var f = CompletableFuture.supplyAsync(() -> {
                try {
                    return mvc.perform(get("/api/v1/health")).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            int status;
            try {
                status = f.get(25, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("DB 가 멈추자 헬스 검사가 25초 안에 응답하지 않았다(끝없이 기다림)");
                return;
            }
            assertThat(status).isEqualTo(503);   // DOWN — 빨리 알린다
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(PG.getContainerId()).exec();
        }
    }
}
