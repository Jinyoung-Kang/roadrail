package com.roadrail.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class OpsDtos {
    private OpsDtos() {}

    public record Job(String job, String provider, String cron, String description, boolean enabled, String lastStatus,
                      OffsetDateTime lastRunAt, Integer lastDurationMs, Integer lastCalls, Integer lastRows,
                      String lastMessage, Double completeness24h, Integer gaps24h, boolean running, boolean warn) {}

    public record Quota(String provider, String day, int limit, int used, int reserved, int remaining) {}

    public record Run(long runId, String job, String trigger, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                      String status, int calls, int rows, String message) {}

    public record ApiError(OffsetDateTime calledAt, String provider, String endpoint, Integer httpStatus, String error) {}

    public record Backfill(String backfillId, String provider, String job, String from, String to, int plannedCalls,
                           int doneDays, String status, OffsetDateTime requestedAt, OffsetDateTime finishedAt) {}

    public record Lag(String series, Double medianMin, Double p90Min, int n) {}

    public record Status(OffsetDateTime asOf, boolean collectorAlive, OffsetDateTime collectorHeartbeat,
                         List<Job> jobs, List<Quota> quota, List<Run> recentRuns, List<ApiError> recentErrors,
                         List<Backfill> backfills, List<Lag> publicationLag, Map<String, Object> volumes) {}

    public record Health(String status, Map<String, String> components, OffsetDateTime asOf) {}

    public record Accepted(String requestId, String job, String status) {}

    public record BackfillRequest(String provider, String job, String from, String to) {}

    public record BackfillAccepted(String backfillId, int plannedCalls, boolean budgetOk, int remainingBudget, String from,
                                   String to) {}
}
