import Link from "next/link";
import { Lines } from "@/components/ui";
import { Headline, plusMin, Stats, TimeBar } from "@/components/trip/parts";
import { DASH, dur, durMin, hm, MODEL_LABEL, num, signedPct } from "@/lib/format";
import { C } from "@/lib/palette";
import { encodePlace } from "@/lib/places";
import { isSlow, slowSummary, TRAFFIC_COLOR, TRAFFIC_LABEL } from "@/lib/traffic";
import { barMax } from "@/lib/trip";
import type { Trip } from "@/lib/types";

/** 경로 소통 띠: 경로를 길이 비율로 펼쳐 지체 · 정체 · 사고 구간을 지도와 같은 색으로 (같은 색이 이어지면 한 칸) */
function TrafficStrip({ trip }: { trip: Trip }) {
  const s = slowSummary(trip.car.path ?? [], trip.car.traffic);
  if (!s.runs.length || s.total <= 0) return null;
  const cells: { color: string; meters: number; slow: boolean }[] = [];
  for (const r of s.runs) {
    const slow = isSlow(r.traffic), color = isSlow(r.traffic) ? TRAFFIC_COLOR[r.traffic] : C.road;
    const last = cells[cells.length - 1];
    if (last && last.color === color) last.meters += r.distanceM;
    else cells.push({ color, meters: r.distanceM, slow });
  }
  const text = s.present.length
    ? s.present.map((k) => (k === "사고" ? `${TRAFFIC_LABEL[k]} ${s.by[k].count}곳` : `${k} ${num(s.by[k].meters / 1000, 1)}km`)).join(" · ")
    : "지체 · 정체 · 사고 구간 없음";
  return (
    <div className="mt-5">
      <p className="flex items-baseline justify-between text-[12px] text-muted">
        <span>경로 소통 · 카카오 예측</span>
        {s.present.length > 0 && <a href="#map" className="text-ink underline underline-offset-2">지도에서 보기</a>}
      </p>
      <div className="mt-1.5 flex h-2 gap-[2px] overflow-hidden rounded-full" role="img" aria-label={`경로 소통: ${text}`}>
        {cells.map((c, i) => <span key={i} style={{ flexGrow: c.meters, flexBasis: 0, minWidth: c.slow ? 3 : 0, background: c.color }} />)}
      </div>
      <p className="mt-1.5 text-[12px] text-ink2">{text}</p>
    </div>
  );
}

export function CarTile({ trip }: { trip: Trip }) {
  const c = trip.car, o = trip.observed, d = trip.decision;
  const pending = c.pending && c.durationSec == null;
  const total = d.carTotalMin ?? (c.durationSec ? Math.round(c.durationSec / 60) : null);
  const drive = c.durationSec ? c.durationSec / 60 : null;
  return (
    <div className="tile flex flex-col p-6 sm:p-8">
      <p className="eyebrow flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-road" aria-hidden />자동차 · 도로</p>
      <Headline total={total} pending={pending} arrive={total == null ? null : plusMin(trip.departAt, total)}
                note={pending ? "카카오 경로 조회 중" : `${hm(trip.departAt)} 출발 · 카카오 미래 운행 예측`} />
      <Stats items={[
        { k: "경로 거리", v: c.distanceM == null ? DASH : `${num(c.distanceM / 1000, 0)}km` },
        { k: "평균 속도", v: c.distanceM && c.durationSec ? `${num(c.distanceM / 1000 / (c.durationSec / 3600), 0)}km/h` : DASH },
        { k: "직선 거리", v: `${num(trip.distanceKm, 0)}km` },
      ]} />
      {drive != null && total != null && (
        <TimeBar max={barMax(trip)} segments={[{ label: "운전", min: drive, className: "bg-road" }]} />
      )}
      <TrafficStrip trip={trip} />
      {o ? (
        <div className="mt-6 rounded-sm ring-1 ring-line">
          <p className="border-b border-line px-4 py-2.5 text-[12px] font-medium text-ink2">
            고속도로 실측 · {o.corridorName} <span className="font-normal text-muted">{hm(o.slotTs)} 기준</span>
          </p>
          <dl className="grid grid-cols-3 divide-x divide-line py-3">
            <div className="px-3 text-center"><dt className="text-[11px] text-muted">지금 구간</dt><dd className="mt-1 text-[15px] font-medium tabular">{dur(o.travelSec)}</dd></div>
            <div className="px-3 text-center"><dt className="text-[11px] text-muted">평소 대비</dt>
              <dd className="mt-1 text-[15px] font-medium tabular">{o.vsBaselinePct == null ? DASH : signedPct(o.vsBaselinePct)}</dd>
              {o.vsBaselinePct == null && <dd className="text-[11px] text-muted">표본 4일 미만</dd>}</div>
            <div className="px-3 text-center"><dt className="text-[11px] text-muted">예측</dt><dd className="mt-1 text-[15px] font-medium tabular">{dur(o.predictedSec)}</dd>
              <dd className="text-[11px] text-muted">{MODEL_LABEL[o.model] ?? o.model} · {durMin(o.leadMin)} 뒤</dd></div>
          </dl>
        </div>
      ) : (
        <p className="mt-6 text-xs leading-relaxed text-muted"><Lines>평소 대비 정체(고속도로 실측)는 수집 중인 길 8개에서만 보입니다. 이 경로는 카카오 예측만 사용합니다.</Lines></p>
      )}
      <div className="mt-auto flex flex-wrap justify-center gap-x-5 pt-6">
        <Link href={`/road?from=${encodeURIComponent(encodePlace(trip.from))}&to=${encodeURIComponent(encodePlace(trip.to))}`}
              className="text-sm font-medium text-ink underline underline-offset-4">경로 분석 보기</Link>
        {o && <Link href={`/road/${o.corridorId}?dir=${o.direction}`} className="text-sm font-medium text-ink underline underline-offset-4">고속도로 실측 보기</Link>}
      </div>
    </div>
  );
}
