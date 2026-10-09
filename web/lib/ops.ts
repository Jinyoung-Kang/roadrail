/** 수집 상태 화면의 규칙 · 변환 — React 에 의존하지 않는다 */
import type { OpsFailure, OpsStatus } from "./types";

export const PROVIDER: Record<string, string> = { EX: "한국도로공사", KORAIL: "한국철도공사", KMA: "기상청", AIRKOREA: "에어코리아", KAKAO: "카카오 길찾기",
  KAKAO_LOCAL: "카카오 검색", TAGO: "TAGO 지하철", TAGO_TRAIN: "TAGO 열차", OSM: "OpenStreetMap", KASI: "천문연 특일 정보", UTIC: "경찰청 UTIC", "-": "내부 계산" };

/** 오류 실행 한 건을 복사할 글로 */
export const failureText = (f: OpsFailure) =>
  `#${f.runId} ${f.job} · ${f.status} · ${f.startedAt} ~ ${f.finishedAt ?? "(종료 기록 없음)"}${f.resolvedAt ? ` · 이후 정상 ${f.resolvedAt}` : ""}\n${f.detail ?? f.message ?? ""}`;

/** 여러 건 — 구분선으로 잇는다 */
export const failuresText = (fs: OpsFailure[]) => fs.map(failureText).join("\n\n" + "─".repeat(40) + "\n\n");

/** 상단 요약 — 길 완전성 · 공개 지연 · 경고 작업 수 · 오류(해결 안 됨) · 작업별 가장 최근 오류 실행 */
export function summarize(d: OpsStatus | null) {
  const failures = d?.failures ?? [];
  const latestFailure: Record<string, number> = {};
  for (const f of failures) latestFailure[f.job] ??= f.runId;   // 목록은 해결 안 된 것 · 최근 순
  return {
    road: d?.jobs.find((j) => j.job === "road_travel_time"),
    lag: d?.publicationLag.find((l) => l.series === "road_travel_time"),
    warnJobs: d?.jobs.filter((j) => j.warn).length ?? 0,
    failures,
    unresolved: failures.filter((f) => !f.resolvedAt).length,
    latestFailure,
    stale: d?.freshness?.filter((f) => f.stale) ?? [],
  };
}

const SERIES: Record<string, string> = { road_travel_time: "고속도로 통행시간", road_volume_all: "고속도로 교통량" };

/** 멈춘 원천 한 줄 — "고속도로 통행시간: 마지막 값이 5일 4시간 전 (기준 6시간)" */
export function staleText(f: { series: string; ageMin: number | null; staleAfterMin: number }) {
  const span = (m: number) => {
    const d = Math.floor(m / 1440), h = Math.floor((m % 1440) / 60), mm = m % 60;
    return d ? `${d}일 ${h}시간` : h ? `${h}시간${mm ? ` ${mm}분` : ""}` : `${mm}분`;
  };
  return `${SERIES[f.series] ?? f.series}: 마지막 값이 ${f.ageMin == null ? "없음" : `${span(f.ageMin)} 전`} (기준 ${span(f.staleAfterMin)})`;
}

/** 예산 막대 — 한도 대비 사용 · 예약 비율 (한도가 0 이면 0) */
export const quotaShares = (q: { limit: number; used: number; reserved: number }) =>
  ({ used: q.limit ? q.used / q.limit : 0, reserved: q.limit ? q.reserved / q.limit : 0 });
