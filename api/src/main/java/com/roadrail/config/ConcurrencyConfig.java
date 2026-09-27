package com.roadrail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ConcurrencyConfig {
    /**
     * 병렬 조회 · 외부 호출용 가상 스레드 실행기 — 서비스마다 만들고 닫지 않던 것을 빈 하나로 (ARC-04).
     * 종료 때 close() 가 실행 중인 작업이 끝나기를 기다린다. DataSource · Redis 를 매개변수로 받아 의존 관계를 남기므로
     * 이 실행기가 먼저 닫혀, 뒤에서 돌던 작업(시간표 받기 등)이 이미 닫힌 연결을 만나지 않는다.
     */
    @Bean(destroyMethod = "close")
    ExecutorService virtualThreads(DataSource dataSource, RedisConnectionFactory redis) {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
