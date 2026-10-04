import Link from "next/link";
import { TrainName } from "@/components/ui";
import { Fold, Headline, plusMin, Stats, Step, TimeBar } from "@/components/trip/parts";
import { DASH, durMin, hm, num, pct } from "@/lib/format";
import { barMax } from "@/lib/trip";
import type { Journey, NextSubway, Trip } from "@/lib/types";

const MODE: Record<string, string> = { WALK: "도보 추정", CAR: "차량 · 카카오 실제 경로", INPUT: "입력값" };

/** 출발지 → 역 → 열차(환승) → 역 → 도착지 — 왼쪽은 시각 */
export function JourneyTimeline({ j, trip }: { j: Journey; trip: Trip }) {
  const start = trip.departAt;
  const end = plusMin(trip.departAt, j.totalMin);
  const rows: React.ReactNode[] = [];
  rows.push(
    <Step key="o" time={hm(start)} tone="ink">
      <p className="font-medium">{trip.from.name} 출발</p>
      <p className="text-xs text-muted">{j.access.stationName}역까지 {j.access.minutes}분 · {MODE[j.access.mode]}{j.access.distanceM ? ` ${num(j.access.distanceM / 1000)}km` : ""}
        {j.waitMin > 0 && ` · 역에서 ${j.waitMin}분 대기`}</p>
    </Step>);
  j.legs.forEach((l, i) => {
    const prev = j.legs[i - 1];
    const gap = prev ? Math.round((new Date(l.dep).getTime() - new Date(prev.arr).getTime()) / 60000) : 0;
    rows.push(
      <Step key={`d${i}`} time={hm(l.dep)} tone="rail">
        <p className="font-medium">{l.fromName}역 {i === 0 ? "승차" : "갈아타기"}{i > 0 && <span className="font-normal text-muted"> · {gap}분 대기</span>}</p>
        <div className="mt-1.5 rounded-sm bg-mist px-3 py-2">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <TrainName trnNo={l.trnNo} meta={l.meta} grade={l.grade} />
          </div>
          <p className="mt-1 text-xs text-muted tabular">
            {durMin(l.rideMin)} 탑승 · {l.onTimeRate30d == null ? "정시율 —" : `정시 ${pct(l.onTimeRate30d)}`} · 평균 지연 {num(l.avgArrDelayMin30d)}분
            {l.samples ? ` · 최근 30일 ${l.samples}회` : ""}
          </p>
        </div>
      </Step>);
    rows.push(
      <Step key={`a${i}`} time={hm(l.arr)} tone={i < j.legs.length - 1 ? "muted" : "rail"}>
        <p className="font-medium">{l.toName}역 하차{i < j.legs.length - 1 && <span className="font-normal text-muted"> (환승)</span>}</p>
        {i === j.legs.length - 1 && (
          <p className="text-xs text-muted">{j.expectedDelayMin && j.expectedDelayMin > 0 ? `평균 ${num(j.expectedDelayMin)}분 늦게 도착 · ` : ""}
            목적지까지 {j.egress.minutes}분 · {MODE[j.egress.mode]}{j.egress.distanceM ? ` ${num(j.egress.distanceM / 1000)}km` : ""}</p>
        )}
      </Step>);
  });
  rows.push(
    <Step key="e" time={hm(end)} tone="ink" last>
      <p className="font-medium">{trip.to.name} 도착 <span className="font-normal text-muted">(지연 반영 예상)</span></p>
    </Step>);
  return <ol>{rows}</ol>;
}

export function SubwayList({ title, items }: { title: string; items: NextSubway[] }) {
  if (!items.length) return null;
  return (
    <div>
      <p className="text-xs font-medium text-ink2">{title}</p>
      <ul className="mt-1.5 space-y-1.5">
        {items.slice(0, 6).map((s) => (
          <li key={s.line + s.toward} className="flex justify-between gap-3 text-xs text-muted">
            <span><span className="rounded-sm bg-cloud px-1.5 py-0.5 text-ink2">{s.line}</span> {s.toward}</span><span className="tabular">{s.times.join(" · ")}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function TrainTile({ trip }: { trip: Trip }) {
  const r = trip.rail;
  const j = r?.journeys[0];
  const ride = j ? j.legs.reduce((t, l) => t + l.rideMin, 0) : 0;
  const transferWait = j ? Math.max((new Date(j.arriveAt).getTime() - new Date(j.departAt).getTime()) / 60000 - ride, 0) : 0;
  const first = j?.legs[0], lastLeg = j?.legs[j.legs.length - 1];
  return (
    <div className="tile flex flex-col p-6 sm:p-8">
      <p className="eyebrow flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-rail" aria-hidden />기차 · 코레일 (환승 포함)</p>
      <Headline total={trip.decision.trainTotalMin} arrive={j ? plusMin(trip.departAt, j.totalMin) : null}
                note={j ? `${j.transfers === 0 ? "직통" : `환승 ${j.transfers}회`} · ${hm(j.departAt)} 열차 · 역 오가는 시간 포함`
                        : r?.note ?? (trip.distanceKm < 15 ? "가까운 거리라 기차 비교를 하지 않습니다" : "기차 정보 없음")} />
      {j && first && lastLeg && (
        <>
          <Stats items={[
            { k: "타는 역", v: `${first.fromName}`, sub: `${j.access.minutes}분 거리` },
            { k: "내리는 역", v: `${lastLeg.toName}`, sub: `${j.egress.minutes}분 거리` },
            { k: "정시율 (30일)", v: lastLeg.onTimeRate30d == null ? DASH : pct(lastLeg.onTimeRate30d),
              sub: j.legs.length > 1 ? "마지막 열차" : lastLeg.samples ? `${lastLeg.samples}회 운행` : undefined },
          ]} />
          <TimeBar max={barMax(trip)} segments={[
            { label: "역까지", min: j.access.minutes, className: "bg-ink2" },
            { label: "대기", min: j.waitMin, className: "bg-line" },
            { label: "탑승", min: ride, className: "bg-rail" },
            { label: "환승 대기", min: transferWait, className: "bg-rail/35" },
            { label: "예상 지연", min: Math.max(j.expectedDelayMin ?? 0, 0), className: "bg-warn" },
            { label: "역에서", min: j.egress.minutes, className: "bg-faint" },
          ]} />
          <div className="mt-6"><JourneyTimeline j={j} trip={trip} /></div>
        </>
      )}
      {r && (r.journeys.length > 1 || r.subwayAtArrival.length > 0 || r.subwayAtDeparture.length > 0) && (
        <div className="mt-5">
          {r.journeys.length > 1 && (
            <Fold title={`다른 여정 ${r.journeys.length - 1}개`}>
              <ul className="space-y-2">
                {r.journeys.slice(1).map((x) => (
                  <li key={x.departAt + x.legs[0].trnNo} className="flex items-baseline justify-between gap-3 text-sm">
                    <span className="min-w-0">
                      <span className="font-medium tabular">{hm(x.departAt)} → {hm(x.arriveAt)}</span>
                      <span className="ml-2 text-xs text-muted">{x.legs[0].fromName}→{x.legs[x.legs.length - 1].toName}
                        {x.transfers ? ` · ${x.legs.slice(1).map((l) => l.fromName).join("·")} 환승` : " · 직통"}
                        {x.legs[0].grade ? ` · ${x.legs[0].grade}` : ""}
</span>
                    </span>
                    <span className="shrink-0 text-xs text-ink2 tabular">총 {durMin(x.totalMin)}</span>
                  </li>
                ))}
              </ul>
            </Fold>
          )}
          {(r.subwayAtArrival.length > 0 || r.subwayAtDeparture.length > 0) && (
            <Fold title="역에서 갈아탈 지하철 (TAGO 시간표)">
              <div className="space-y-4">
                <SubwayList title={`${lastLeg?.toName ?? ""}역 도착 후`} items={r.subwayAtArrival} />
                <SubwayList title={`${first?.fromName ?? ""}역 출발 전`} items={r.subwayAtDeparture} />
              </div>
            </Fold>
          )}
        </div>
      )}
      {j && r?.referenceDate && (
        <p className="mt-3 text-xs leading-relaxed text-muted">시간표 기준 {r.referenceDate} ({r.basis}) — 앞으로의 시간표는 공개 데이터에 없어 최근 같은 요일의 실제 시간표(코레일 운행계획 · TAGO)를 씁니다{j.legs.some((l) => !l.timetable) && " · ⚠ 일부 구간은 시간표를 받지 못해 보간한 시각"} · 근처 역 {r.originCandidates}×{r.destCandidates}곳 조합 · 승차 여유 {r.boardingBufferMin}분 · 최소 환승 {r.transferMin}분 · {r.note}</p>
      )}
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
