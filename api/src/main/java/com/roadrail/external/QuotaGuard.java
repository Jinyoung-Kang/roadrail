package com.roadrail.external;

import com.roadrail.common.Times;
import com.roadrail.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 공급자별 일일 호출 예산 — collector 의 QuotaBudget 과 **같은 Redis 키 · 같은 Lua 스크립트**를 쓴다 (docs/redis-keys.md).
 * api 가 조회 시점에 카카오 · 기상청 · 에어코리아를 직접 부르므로(어디서 → 어디로 자유 선택) 두 프로세스의 호출을 합쳐
 * 한도를 넘지 않게 한다 (NFR-02). Redis 가 없으면 호출하지 않는다 (보수적).
 */
@Component
public class QuotaGuard {
    private static final Logger log = LoggerFactory.getLogger(QuotaGuard.class);
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
            local n = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])
            if cur + n > limit then return -1 end
            local v = redis.call('INCRBY', KEYS[1], n)
            redis.call('EXPIRE', KEYS[1], 172800)
            return v
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AppProperties props;

    public QuotaGuard(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 1건 예약 + 사용 기록. 예산이 없거나 Redis 오류면 false. */
    public boolean take(String provider) {
        String day = Times.now().format(YMD);
        try {
            Long v = redis.execute(RESERVE, List.of("quota:" + provider + ":" + day), "1", String.valueOf(props.quotaOf(provider)));
            if (v == null || v < 0) {
                log.warn("{} 일일 예산 소진 — 호출하지 않음", provider);
                return false;
            }
            String used = "quota:used:" + provider + ":" + day;
            redis.opsForValue().increment(used);
            redis.expire(used, Duration.ofHours(48));
            return true;
        } catch (RuntimeException e) {
            log.warn("예산 확인 실패({}) — 호출하지 않음: {}", provider, e.getMessage());
            return false;
        }
    }
}
