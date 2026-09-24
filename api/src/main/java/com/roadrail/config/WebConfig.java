package com.roadrail.config;

import com.roadrail.common.AdminTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AdminTokenInterceptor adminToken;

    public WebConfig(AdminTokenInterceptor adminToken) { this.adminToken = adminToken; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminToken).addPathPatterns("/api/v1/admin/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 화면은 Next.js rewrites 로 같은 출처에서 호출하지만, 개발 중 직접 호출을 위해 로컬만 허용
        registry.addMapping("/api/**").allowedOrigins("http://localhost:3300", "http://127.0.0.1:3300")
                .allowedMethods("GET", "POST");
    }
}
