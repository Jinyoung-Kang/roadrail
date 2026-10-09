import assert from "node:assert/strict";
import { test } from "node:test";
import { failureText, quotaShares, staleText, summarize } from "./ops.ts";
import type { OpsFailure, OpsStatus } from "./types";

const f = (runId: number, job: string, resolvedAt: string | null): OpsFailure => ({
  runId, job, trigger: "SCHEDULE", startedAt: "2026-10-04T10:00:00+09:00", finishedAt: null, status: "FAILED",
  message: "m", detail: null, resolvedAt,
} as OpsFailure);

test("요약: 해결 안 된 오류 수 · 작업별 가장 최근(목록 첫) 오류 실행 · 경고 작업", () => {
  const d = { jobs: [{ job: "road_travel_time", warn: true }, { job: "kakao_eta", warn: false }],
    publicationLag: [{ series: "road_travel_time", medianMin: 80 }],
    failures: [f(9, "kakao_eta", null), f(7, "kakao_eta", "x"), f(5, "rail_daily", "y")] } as unknown as OpsStatus;
  const s = summarize(d);
  assert.equal(s.unresolved, 1);
  assert.deepEqual(s.latestFailure, { kakao_eta: 9, rail_daily: 5 });
  assert.equal(s.warnJobs, 1);
  assert.equal(s.lag?.medianMin, 80);
  assert.deepEqual(summarize(null), { road: undefined, lag: undefined, warnJobs: 0, failures: [], unresolved: 0, latestFailure: {}, stale: [] });
});

test("오류 복사 글 · 예산 비율", () => {
  assert.equal(failureText(f(3, "x", null)), "#3 x · FAILED · 2026-10-04T10:00:00+09:00 ~ (종료 기록 없음)\nm");
  assert.deepEqual(quotaShares({ limit: 200, used: 50, reserved: 10 }), { used: 0.25, reserved: 0.05 });
  assert.deepEqual(quotaShares({ limit: 0, used: 5, reserved: 1 }), { used: 0, reserved: 0 });
});

test("멈춘 원천: 기준을 넘긴 계열만 · 사람이 읽는 기간", () => {
  const d = { jobs: [], publicationLag: [], failures: [], freshness: [
    { series: "road_travel_time", job: "road_travel_time", latestAt: "x", ageMin: 7465, staleAfterMin: 360, stale: true },
    { series: "road_volume_all", job: "road_volume_all", latestAt: "y", ageMin: 50, staleAfterMin: 180, stale: false }] } as unknown as OpsStatus;
  const s = summarize(d);
  assert.deepEqual(s.stale.map((f) => f.series), ["road_travel_time"]);
  assert.equal(staleText(s.stale[0]), "고속도로 통행시간: 마지막 값이 5일 4시간 전 (기준 6시간)");
  assert.equal(staleText({ series: "road_volume_all", ageMin: 95, staleAfterMin: 180 }), "고속도로 교통량: 마지막 값이 1시간 35분 전 (기준 3시간)");
});
