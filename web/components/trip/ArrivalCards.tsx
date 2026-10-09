import Link from "next/link";
import { Lines, TrainName } from "@/components/ui";
import { Fold, Step } from "@/components/trip/parts";
import { DASH, durMin, hm } from "@/lib/format";
import { oddsText } from "@/lib/arrival";
import type { Arrival } from "@/lib/types";

const MODE: Record<string, string> = { WALK: "도보 추정", CAR: "차량 · 카카오 경로", INPUT: "입력값" };

/** "늦어도 HH:mm 출발" — 카드 머리. 모르면 대시와 이유 */
function Latest({ at, pending, note }: { at: string | null; pending: boolean; note: string }) {
  return (
    <div className="mt-4">
      <p className="text-[13px] text-muted">늦어도</p>
      <p className="text-[40px] font-medium leading-none tabular sm:text-[44px]">{pending && !at ? "…" : at ? `${hm(at)} 출발` : DASH}</p>
      <p className="mt-2 min-h-[2.6em] text-[13px] text-muted"><Lines>{note}</Lines></p>
    </div>
  );
}

/** 기차 — 신뢰 수준을 만족하는 가장 늦은 여정과 그 근거(빈도) */
function TrainCard({ a }: { a: Arrival }) {
  const t = a.train, j = t.journey;
  const odds = oddsText(t.odds, t.legRisks);
  const pct = Math.round(a.confidence * 100);
  const percent = t.odds?.percent ?? null;
  return (
    <div className="tile flex min-h-[460px] flex-col p-6 sm:p-8">
      <p className="eyebrow flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-rail" aria-hidden />기차 · 최근 30일 실제 지연으로 계산</p>
      <Latest at={t.latestDepart} pending={a.pending}
              note={j ? `${hm(j.arriveAt)} 도착 · ${j.transfers === 0 ? "직통" : `환승 ${j.transfers}회`} · 역 오가는 시간 포함` : t.note ?? "기차 정보 없음"} />
      {t.latestDepart && (
        <div className="mt-5 rounded-sm bg-mist px-4 py-3">
          <p className="flex items-baseline justify-between gap-3">
            <span className="text-[12px] text-muted">기한 안 도착</span>
            <span className={`text-sm font-medium ${t.meetsConfidence ? "text-ink" : "text-serious"}`}>
              {t.meetsConfidence ? `✓ ${pct}% 기준 만족` : `! ${pct}% 기준 미달`}
            </span>
          </p>
          <p className="mt-1 text-[22px] font-medium tabular">{odds.main}</p>
          {percent != null && (
            <div className="mt-1.5 h-1.5 w-full rounded-full bg-line" aria-hidden>
              <div className="h-full rounded-full bg-rail" style={{ width: `${percent}%` }} />
            </div>
          )}
          <p className="mt-1.5 text-[12px] text-ink2">{odds.sub}</p>
          {t.odds && <p className="mt-0.5 text-[11px] text-muted">{t.odds.basis}</p>}
        </div>
      )}
      {j && (
        <ol className="mt-5">
          <Step time={hm(t.latestDepart)} tone="ink">
            <p className="font-medium">{a.from.name} 출발</p>
            <p className="text-xs text-muted">{j.access.stationName}역까지 {j.access.minutes}분 · {MODE[j.access.mode]} · 승차 여유 {j.waitMin}분</p>
          </Step>
          {j.legs.map((l, i) => {
            const risk = t.legRisks[i];
            return (
              <Step key={l.trnNo + i} time={hm(l.dep)} tone="rail">
                <p className="font-medium">{l.fromName}역 {i === 0 ? "승차" : "갈아타기"} → {l.toName}역 {hm(l.arr)}</p>
                <div className="mt-1 flex flex-wrap items-center gap-x-2 text-xs text-muted">
                  <TrainName trnNo={l.trnNo} meta={l.meta} grade={l.grade} />
                  {risk && <span className="tabular">{risk.kind === "TRANSFER" ? `환승 여유 ${risk.allowanceMin}분` : `기한까지 여유 ${risk.allowanceMin}분`} · {risk.n}회 중 {risk.within}회 안</span>}
                </div>
              </Step>
            );
          })}
          <Step time={hm(a.arriveBy)} tone="ink" last>
            <p className="font-medium">{a.to.name} 도착 기한</p>
            <p className="text-xs text-muted">{j.egress.stationName}역에서 {j.egress.minutes}분 · {MODE[j.egress.mode]}</p>
          </Step>
        </ol>
      )}
      {t.alternatives.length > 0 && (
        <div className="mt-4">
          <Fold title={`다른 후보 ${t.alternatives.length}개`}>
            <ul className="space-y-2">
              {t.alternatives.map((x) => (
                <li key={x.latestDepart + x.arriveAt} className="flex items-baseline justify-between gap-3 text-sm">
                  <span className="tabular"><b className="font-medium">{hm(x.latestDepart)} 출발</b> → {hm(x.arriveAt)} 역 도착
                    <span className="ml-2 text-xs text-muted">{x.transfers ? `환승 ${x.transfers}회` : "직통"}</span></span>
                  <span className="shrink-0 text-xs text-ink2 tabular">{oddsText(x.odds).main}</span>
                </li>
              ))}
            </ul>
          </Fold>
        </div>
      )}
      {j && t.note && <p className="mt-3 text-xs leading-relaxed text-muted"><Lines>{t.note}</Lines></p>}
      {j && (
        <div className="mt-auto flex flex-wrap justify-center gap-x-5 pt-6">
          {j.legs.map((l) => (
            <Link key={l.trnNo + l.fromCode} href={`/rail?dep=${l.fromCode}&arr=${l.toCode}`} className="text-sm font-medium text-ink underline underline-offset-4">
              {l.fromName}→{l.toName} 철도 분석
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

/** 자동차 — 카카오 예측 소요로 기한 안에 도착하는 가장 늦은 10분 칸. 확률은 내지 않는다 */
function CarCard({ a }: { a: Arrival }) {
  const c = a.car;
  const arrive = c.latestDepart && c.durationSec != null ? new Date(Date.parse(c.latestDepart) + c.durationSec * 1000).toISOString() : null;
  return (
    <div className="tile flex min-h-[460px] flex-col p-6 sm:p-8">
      <p className="eyebrow flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-road" aria-hidden />자동차 · 카카오 미래 운행 정보</p>
      <Latest at={c.latestDepart} pending={a.pending}
              note={arrive ? `예측 소요 ${durMin(c.durationSec! / 60)} → ${hm(arrive)} 도착 예상` : c.note ?? ""} />
      <div className="mt-5 rounded-sm bg-mist px-4 py-3">
        <p className="text-[12px] text-muted">기한 안 도착 확률</p>
        <p className="mt-1 text-[22px] font-medium">확률 없음</p>
        <p className="mt-1.5 text-[12px] text-ink2">{c.basis}</p>
        <p className="mt-0.5 text-[11px] text-muted">출발 시각은 10분 단위 · 예측 조회 {c.calls}번</p>
      </div>
    </div>
  );
}

/** 도착 시각 기준 결과 두 장 — 높이를 미리 잡아 결과가 와도 아래가 밀리지 않는다 */
export function ArrivalCards({ a }: { a: Arrival }) {
  return <div className="grid gap-6 lg:grid-cols-2"><CarCard a={a} /><TrainCard a={a} /></div>;
}

export function ArrivalAssumptions({ a }: { a: Arrival }) {
  return (
    <div className="tile p-6 sm:p-8">
      <p className="text-sm font-medium">{a.summary}</p>
      <ul className="mt-4 list-disc space-y-1.5 pl-5 text-sm text-ink2">
        {a.assumptions.map((s) => <li key={s}>{s}</li>)}
      </ul>
      <p className="mt-4 text-xs text-muted">규칙 AR-v1 · 도착 기한 {hm(a.arriveBy)} · 신뢰 수준 {Math.round(a.confidence * 100)}% · 계산 {hm(a.asOf)} · 캐시 {a.cache}</p>
    </div>
  );
}
