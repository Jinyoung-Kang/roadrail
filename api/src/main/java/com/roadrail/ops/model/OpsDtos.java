package com.roadrail.ops.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class OpsDtos {
    private OpsDtos() {}

    public record Job(String job, String provider, String cron, String description, boolean enabled, String lastStatus,
                      OffsetDateTime lastRunAt, Integer lastDurationMs, Integer lastCalls, Integer lastRows,
                      String lastMessage, Double completeness24h, Integer gaps24h,
                      /** 원천에 표본이 없는 슬롯 — 결측 · 완전성 분모에서 뺀다(전체를 다시 받아도 빔) */
                      Integer noSamples24h, boolean running, boolean warn) {}

    public record Quota(String provider, String day, int limit, int used, int reserved, int remaining) {}

    public record Run(long runId, String job, String trigger, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                      String status, int calls, int rows, String message) {}

    public record ApiError(OffsetDateTime calledAt, String provider, String endpoint, Integer httpStatus, String error) {}

    public record Backfill(String backfillId, String provider, String job, String from, String to, int plannedCalls,
                           int doneDays, String status, OffsetDateTime requestedAt, OffsetDateTime finishedAt) {}

    public record Lag(String series, Double medianMin, Double p90Min, int n) {}

    /**
     * 최근 24시간 실패·부분 성공·예산 부족 실행과 그 전체 내용(작업 메모 · 실패한 외부 호출 · 스택 트레이스, 키 마스킹).
     * resolvedAt = 같은 작업이 그 뒤 처음 정상(OK) 종료한 시각 (없으면 null = 아직 해결 안 됨).
     */
    public record Failure(long runId, String job, String trigger, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                          String status, String message, String detail, OffsetDateTime resolvedAt) {}

    /**
     * detailed = 오류 상세 포함 여부. 공개 경로(/ops/collect-status)는 false — 실패한 실행의 메시지 · 스택 트레이스 · 외부 호출 주소와
     * 오류 문구를 비운다(상태 · 시각 · 건수는 그대로). 관리 경로(/admin/collect-status, X-Admin-Token)는 true.
     */
    public record Status(OffsetDateTime asOf, boolean collectorAlive, OffsetDateTime collectorHeartbeat,
                         List<Job> jobs, List<Quota> quota, List<Run> recentRuns, List<ApiError> recentErrors,
                         List<Failure> failures, List<Backfill> backfills, List<Lag> publicationLag,
                         Map<String, Object> volumes, boolean detailed) {}

    public record Health(String status, Map<String, String> components, OffsetDateTime asOf) {}

    public record Accepted(String requestId, String job, String status) {}

    public record BackfillRequest(String provider, String job, String from, String to) {}

    public record BackfillAccepted(String backfillId, int plannedCalls, boolean budgetOk, int remainingBudget, String from,
                                   String to) {}
}
