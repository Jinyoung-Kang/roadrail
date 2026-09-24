package com.roadrail.service;

import com.roadrail.common.ApiException;
import com.roadrail.common.ErrorCode;
import com.roadrail.common.Times;
import com.roadrail.common.TraceIdFilter;
import com.roadrail.web.dto.OpsDtos.*;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 관리 명령 → Redis Stream rr:commands → collector 가 실행 (ADR-009).
 * API 는 실행 중 여부(rr:lock:{job})와 예산을 먼저 확인해 409 / 429 로 거절한다.
 */
@Service
public class AdminService {
    public static final String STREAM = "rr:commands";
    public static final int RAIL_EARLIEST_DAYS = 92;
    public static final int RAIL_CALLS_PER_DAY = 3;
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final OpsService ops;

    public AdminService(JdbcClient jdbc, StringRedisTemplate redis, OpsService ops) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.ops = ops;
    }

    public Accepted runJob(String job) {
        boolean known = jdbc.sql("SELECT EXISTS (SELECT 1 FROM ops.collect_job WHERE job_name = :j)").param("j", job)
                .query(Boolean.class).single();
        if (!known) throw new ApiException(ErrorCode.NOT_FOUND, "작업 '" + job + "' 이 없습니다.");
        ensureNotRunning(job);
        String id = TraceIdFilter.ulid();
        redis.opsForStream().add(StreamRecords.string(Map.of("type", "run_job", "job", job, "requestId", id)).withStreamKey(STREAM));
        return new Accepted(id, job, "QUEUED");
    }

    public BackfillAccepted backfill(BackfillRequest req) {
        if (req == null || req.provider() == null || req.from() == null || req.to() == null) {
            throw ApiException.invalid("provider · job · from · to 가 필요합니다.");
        }
        if (!"KORAIL".equals(req.provider()) || !(req.job() == null || "rail_daily".equals(req.job()))) {
            throw ApiException.invalid("기간 재수집은 KORAIL rail_daily 만 지원합니다 — 도로공사 통행시간은 당일만, "
                    + "단기예보·대기질은 과거 조회를 제공하지 않습니다 (결측은 road_gap_backfill 이 당일 안에서 재시도).");
        }
        LocalDate from, to;
        try {
            from = LocalDate.parse(req.from());
            to = LocalDate.parse(req.to());
        } catch (DateTimeParseException e) {
            throw ApiException.invalid("from · to 는 YYYY-MM-DD 형식입니다.");
        }
        LocalDate today = Times.now().toLocalDate();
        if (to.isBefore(from)) throw ApiException.invalid("to 는 from 이후여야 합니다.");
        if (!to.isBefore(today)) throw ApiException.invalid("운행정보는 어제까지만 조회할 수 있습니다 (to < " + today + ").");
        if (from.isBefore(today.minusDays(RAIL_EARLIEST_DAYS))) {
            throw ApiException.invalid("운행정보 요청 기간이 3개월 전보다 이전입니다 (from ≥ " + today.minusDays(RAIL_EARLIEST_DAYS) + ").");
        }
        int planned = (int) (ChronoUnit.DAYS.between(from, to) + 1) * RAIL_CALLS_PER_DAY;
        int remaining = ops.remaining("KORAIL");
        if (planned > remaining) {
            throw new ApiException(ErrorCode.QUOTA_EXHAUSTED,
                    "KORAIL 일일 예산이 부족합니다 (예상 " + planned + "건, 남은 예산 " + remaining + "건).");
        }
        ensureNotRunning("rail_backfill");
        String id = TraceIdFilter.ulid();
        jdbc.sql("""
                INSERT INTO ops.backfill (backfill_id, provider, job_name, from_date, to_date, planned_calls)
                VALUES (:id, 'KORAIL', 'rail_daily', :f, :t, :p)""")
                .param("id", id).param("f", from).param("t", to).param("p", planned).update();
        redis.opsForStream().add(StreamRecords.string(Map.of("type", "backfill", "backfillId", id)).withStreamKey(STREAM));
        return new BackfillAccepted(id, planned, true, remaining, from.toString(), to.toString());
    }

    private void ensureNotRunning(String job) {
        if (Boolean.TRUE.equals(redis.hasKey("rr:lock:" + job))) {
            throw new ApiException(ErrorCode.JOB_RUNNING, "작업 '" + job + "' 이 이미 실행 중입니다.");
        }
    }
}
