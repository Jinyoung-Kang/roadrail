package com.roadrail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RoadRailApplication {
    /** JVM 의 DNS 결과 보관 시간(초) — 기본 30초면 컨테이너가 새 주소로 다시 떠도 그동안 옛 주소를 쓴다(믿는 프록시 · Redis · DB 이름) */
    static final String DNS_TTL_SECONDS = "5";

    public static void main(String[] args) {
        java.security.Security.setProperty("networkaddress.cache.ttl", DNS_TTL_SECONDS);  // 첫 이름 조회 전에
        SpringApplication.run(RoadRailApplication.class, args);
    }
}
