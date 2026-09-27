package com.roadrail.external;

import com.roadrail.common.ClientContext;
import com.roadrail.common.Times;
import com.roadrail.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 공급자별 일일 호출 예산 — collector 의 QuotaBudget 과 **같은 Redis 키**(예약 누계 · 사용 수)를 쓴다 (docs/redis-keys.md).
 * api 가 조회 시점에 카카오 · 기상청 · 에어코리아 · TAGO 를 직접 부르므로(어디서 → 어디로 자유 선택) 두 프로세스의 호출을 합쳐
 * 한도를 넘지 않게 한다 (NFR-02). Redis 가 없으면 호출하지 않는다 (보수적).
 * <p>
 * 요청에서 비롯된 호출은 **클라이언트별 몫**(일일 한도의 client-share-pct %, 기본 20%)도 넘지 않게 한다 — 한 클라이언트가
 * 새 조합을 반복 요청해 모두가 쓰는 하루 예산을 소진하는 것을 막는다 (SEC-02). 요청 수가 아니라 실제 외부 호출만 세므로
 * 캐시 적중 · 화면 자동 갱신은 몫을 쓰지 않는다. 전체 한도 · 몫 확인 · 증가를 Lua 한 번(Redis 왕복 1회)으로 원자적으로 한다.
 */
@Component
public class QuotaGuard {
    private static final Logger log = LoggerFactory.getLogger(QuotaGuard.class);
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** KEYS = 예약 누계 · 사용 수 · 클라이언트 몫, ARGV = 일일 한도 · 클라이언트 몫(0 = 적용 안 함). 반환: 새 누계, -1 전체 한도, -2 클라이언트 몫 */
    private static final DefaultRedisScript<Long> TAKE = new DefaultRedisScript<>("""
            local limit, share = tonumber(ARGV[1]), tonumber(ARGV[2])
            if tonumber(redis.call('GET', KEYS[1]) or '0') + 1 > limit then return -1 end
            if share > 0 then
              if tonumber(redis.call('GET', KEYS[3]) or '0') + 1 > share then return -2 end
              redis.call('INCR', KEYS[3])
              redis.call('EXPIRE', KEYS[3], 172800)
            end
            local v = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], 172800)
            redis.call('INCR', KEYS[2])
            redis.call('EXPIRE', KEYS[2], 172800)
            return v
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AppProperties props;
    private volatile long lastShareWarn = 0;

    public QuotaGuard(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 1건 예약 + 사용 기록. 전체 예산 · 클라이언트 몫이 없거나 Redis 오류면 false (호출하지 않음). */
    public boolean take(String provider) {
        String day = Times.now().format(YMD);
        int limit = props.quotaOf(provider);
        String client = ClientContext.current();
        int share = client == null || props.clientSharePct() <= 0 ? 0 : Math.max(limit * props.clientSharePct() / 100, 1);
        try {
            Long v = redis.execute(TAKE, List.of("quota:" + provider + ":" + day, "quota:used:" + provider + ":" + day,
                    "quota:client:" + provider + ":" + day + ":" + (client == null ? "-" : client)),
                    String.valueOf(limit), String.valueOf(share));
            if (v == null || v == -1) {
                log.warn("{} 일일 예산 소진 — 호출하지 않음", provider);
                return false;
            }
            if (v == -2) {
                long now = System.currentTimeMillis();
                if (now - lastShareWarn > 60_000) {
                    lastShareWarn = now;
                    log.warn("{} 클라이언트 몫({}건/일) 소진 — 이 클라이언트의 호출은 내일까지 하지 않음: {}", provider, share, client);
                }
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("예산 확인 실패({}) — 호출하지 않음: {}", provider, e.getMessage());
            return false;
        }
    }
}
