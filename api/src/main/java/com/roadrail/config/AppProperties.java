package com.roadrail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "roadrail")
public record AppProperties(
        String adminToken,
        String kakaoRestApiKey,
        String dataGoKrKey,
        String kakaoMobilityBaseUrl,
        int onTimeThresholdMin,
        int decisionSimilarMin,
        int decisionPopWarn,
        int defaultAccessMin,
        int forecastTauMin,
        int nowCacheSeconds,
        int roadStaleMinutes,
        Map<String, Integer> quota,
        /** 클라이언트 IP 별 분당 요청 한도 (admin · trip · route · search · rail), 0 이면 끔 */
        Map<String, Integer> rateLimit) {

    public int quotaOf(String provider) {
        return quota == null ? 0 : quota.getOrDefault(provider, 0);
    }
}
