package com.roadrail.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("RoadRail API").version("v1")
                .description("고속도로·열차 이동 판단 & 정시성 분석. 시각은 ISO-8601(+09:00), 소요시간은 Sec/Min 접미사로 단위 표시. "
                        + "판단은 참고 정보이며 교통 안내 서비스가 아닙니다."));
    }
}
