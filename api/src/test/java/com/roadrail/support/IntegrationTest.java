package com.roadrail.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;

/** PostgreSQL 16 · Redis 7 은 Testcontainers. Flyway 는 저장소의 db/migrations 를 그대로 적용한다. */
@SpringBootTest
public abstract class IntegrationTest {
    public static final String ADMIN = "test-admin-token";
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
        r.add("roadrail.admin-token", () -> ADMIN);
        r.add("roadrail.kakao-rest-api-key", () -> "");
    }

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void resetRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }
}
