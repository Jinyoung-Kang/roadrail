import { TRAFFIC_COLOR, TRAFFIC_LABEL, type SlowState } from "@/lib/traffic";

/** 지도 범례의 느린 구간 줄 — 경로에 있는 소통만. 사고는 지도와 같이 점선 */
export default function TrafficLegend({ states, note = "카카오 예측" }: { states: SlowState[]; note?: string }) {
  if (!states.length) return null;
  return (
    <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted">
      {states.map((s) => (
        <span key={s} className="flex items-center gap-1.5">
          <span className="inline-block h-0 w-5 border-t-[4px]"
                style={{ borderColor: TRAFFIC_COLOR[s], borderStyle: s === "사고" ? "dashed" : "solid" }} aria-hidden />
          {TRAFFIC_LABEL[s]}
        </span>
      ))}
      <span className="text-muted">{note}</span>
    </p>
  );
}
