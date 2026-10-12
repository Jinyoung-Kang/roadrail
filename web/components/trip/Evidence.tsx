import { useState } from "react";
import { Lines } from "@/components/ui";
import { hm, mdhm, num } from "@/lib/format";
import type { Decision, Incident } from "@/lib/types";

/**
 * 돌발 안내 한 건 — 받은 안내 그대로(유형 · 노선 · 방향 · 안내 구간 · 본문)와 출처.
 * 시각의 뜻은 출처마다 다르다: 도로공사 문자는 발송 시각, 경찰청 UTIC 는 돌발 시작(· 종료 예정)과 통제 차로.
 * 위치는 응답에 좌표가 있을 때만 '지도에서 보기' — 없으면 '위치 정보 없음'(추정하지 않음).
 */
export function IncidentItem({ i, onLocate }: { i: Incident; onLocate?: (i: Incident) => void }) {
  const located = i.lat != null && i.lon != null;
  const utic = i.source === "UTIC";
  const when = utic ? `${mdhm(i.sentAt)} 시작${i.endAt ? ` · ${mdhm(i.endAt)} 종료 예정` : ""}` : `${mdhm(i.sentAt)} 발송`;
  return (
    <li className="rounded-sm bg-white px-4 py-3 ring-1 ring-black/5">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
        <span className="rounded-sm bg-[#fff1cc] px-1.5 py-0.5 text-[11px] font-medium text-ink">{i.typeName || "돌발"}</span>
        <span className="font-medium text-ink">{i.routeName}</span>
        {i.direction && <span className="text-ink2">{i.direction}</span>}
        {i.process && <span className="text-xs text-muted">· {i.process}</span>}
        {i.lane && <span className="text-xs text-muted">· {i.lane}</span>}
        <span className="ml-auto text-[11px] text-muted">{utic ? "경찰청 UTIC" : "도로공사"}</span>
      </div>
      {i.pointName && <p className="mt-1 text-xs text-ink2">{utic ? "위치" : "구간"} {i.pointName}</p>}
      <p className="mt-1 text-sm leading-relaxed text-ink2">{i.content}</p>
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2 text-xs text-muted">
        <span>{when}{i.routeKm != null ? ` · 안내 지점이 자동차 경로에서 ${num(i.routeKm)}km` : located ? "" : " · 위치 정보 없음 (수집 중인 길과 같은 노선)"}</span>
        {located && onLocate && (
          <button type="button" onClick={() => onLocate(i)} className="font-medium text-accent hover:underline">지도에서 보기 ↓</button>
        )}
      </div>
    </li>
  );
}

/** 처음 몇 건만 펼치는 돌발 목록 — 도로공사 문자에 UTIC(일반 도로)까지 더해 긴 경로는 10건을 넘기도 한다 */
const INCIDENT_FOLD = 5;

function IncidentList({ incidents, total, onLocate }: { incidents: Incident[]; total: number; onLocate?: (i: Incident) => void }) {
  const [open, setOpen] = useState(false);
  const shown = open ? incidents : incidents.slice(0, INCIDENT_FOLD);
  return (
    <>
      <ul id="incident-list" className="mb-1 mt-2 space-y-2" aria-label="돌발 안내 목록">
        {shown.map((i) => <IncidentItem key={i.sentAt + i.content} i={i} onLocate={onLocate} />)}
      </ul>
      {incidents.length > INCIDENT_FOLD && (
        <button type="button" aria-expanded={open} aria-controls="incident-list" onClick={() => setOpen(!open)}
                className="mb-1 mt-1 text-xs font-medium text-accent hover:underline">
          {open ? "접기" : `나머지 ${incidents.length - INCIDENT_FOLD}건 더 보기`}
        </button>
      )}
      {total > incidents.length && <p className="mb-1 mt-1 text-xs text-muted">최근 {incidents.length}건만 보여 줍니다 (전체 {total}건)</p>}
    </>
  );
}

export function Evidence({ decision, freshness, caveat, cache, asOf, incidents = [], incidentTotal, onLocate }: {
  decision: Decision; freshness: Record<string, string>; caveat: string; cache: string; asOf: string;
  incidents?: Incident[]; incidentTotal?: number; onLocate?: (i: Incident) => void;
}) {
  const labels: Record<string, string> = { kakao: "카카오 경로", road: "고속도로 실측", rail: "열차 정시성", weather: "단기예보", air: "대기질" };
  return (
    <div className="grid gap-6 lg:grid-cols-[1.4fr_1fr]">
      <div className="tile p-6 sm:p-8">
        <p className="eyebrow">판단 근거 · {decision.rule}</p>
        <p className="mt-2 text-xl font-medium">{decision.summary}</p>
        <ol className="mt-5 space-y-3">
          {decision.reasons.map((r, i) => (
            <li key={i} className="flex gap-3 text-sm text-ink2">
              <span className="mt-0.5 flex h-5 w-5 flex-none items-center justify-center rounded-full bg-cloud text-[11px] font-medium text-ink">{i + 1}</span>
              <span>{r}</span>
            </li>
          ))}
        </ol>
        {decision.warnings.length > 0 && (
          <ul className="mt-5 space-y-2">
            {decision.warnings.map((w) => (
              <li key={w} className="rounded-sm bg-[#fff7e6] px-3 py-2 text-sm text-ink2">
                <p className="flex items-center gap-2"><span className="text-warn" aria-hidden>▲</span><span className="sr-only">경고: </span>{w}</p>
                {w.includes("돌발") && incidents.length > 0 && (
                  <IncidentList incidents={incidents} total={Math.max(incidentTotal ?? 0, incidents.length)} onLocate={onLocate} />
                )}
              </li>
            ))}
          </ul>
        )}
        <p className="mt-5 text-xs leading-relaxed text-muted"><Lines>{caveat}</Lines></p>
      </div>
      <div className="tile p-6 sm:p-8">
        <p className="eyebrow">데이터 시각</p>
        <dl className="mt-3">
          {Object.entries(labels).filter(([k]) => freshness[k]).map(([k, label]) => (
            <div key={k} className="flex justify-between gap-3 border-b border-line py-2.5 text-sm last:border-0">
              <dt className="text-muted">{label}</dt><dd className="text-right text-ink2">{freshness[k]}</dd>
            </div>
          ))}
        </dl>
        <p className="mt-4 text-xs text-muted">응답 캐시 {cache} · {hm(asOf)} 계산</p>
      </div>
    </div>
  );
}
