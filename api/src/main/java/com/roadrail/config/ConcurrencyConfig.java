package com.roadrail.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Configuration
public class ConcurrencyConfig {
    /** 종료 때 실행 중인 작업을 기다리는 최대 시간 — compose 기본 종료 유예(10초) 안에서 */
    static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    /**
     * 병렬 조회 · 외부 호출용 가상 스레드 실행기 — 서비스마다 만들고 닫지 않던 것을 빈 하나로 (ARC-04).
     * DataSource · Redis 를 매개변수로 받아 의존 관계를 남기므로 이 실행기가 연결보다 먼저 정리된다.
     * 종료는 아래 virtualThreadsShutdown 이 제한 시간 안에서 한다(close() 는 기다림에 상한이 없어 추론을 끈다).
     */
    @Bean(destroyMethod = "")
    ExecutorService virtualThreads(DataSource dataSource, RedisConnectionFactory redis) {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 실행기에 의존하므로 실행기(와 DataSource · Redis)보다 먼저 종료된다 */
    @Bean
    DisposableBean virtualThreadsShutdown(ExecutorService virtualThreads) {
        return () -> shutdown(virtualThreads, SHUTDOWN_GRACE);
    }

    /** 새 작업을 받지 않고 grace 까지 기다린 뒤, 남은 작업(뒤에 쌓인 시간표 받기 등)은 중단 — 다음 기동 때 다시 받는다 */
    static void shutdown(ExecutorService exec, Duration grace) throws InterruptedException {
        exec.shutdown();
        if (!exec.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) exec.shutdownNow();
    }
}
