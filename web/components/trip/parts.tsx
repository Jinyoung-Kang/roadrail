/** 판단 카드들이 함께 쓰는 작은 조각 — 큰 소요 시간 · 지표 · 시간 구성 막대 · 시간표 단계 · 접기 */
import { Lines } from "@/components/ui";
import { DASH, durMin, hm } from "@/lib/format";

/** 분 → ISO 시각 */
export const plusMin = (iso: string, min: number) => new Date(new Date(iso).getTime() + min * 60_000).toISOString();

/** 큰 소요 시간 + 도착 예정 시각 */
export function Headline({ total, pending, arrive, note }: { total: number | null; pending?: boolean; arrive: string | null; note: string }) {
  return (
    <div className="mt-4 flex items-end justify-between gap-4">
      <div>
        <p className="text-[40px] font-medium leading-none tabular sm:text-[44px]">{pending ? "…" : durMin(total)}</p>
        <p className="mt-2 text-[13px] text-muted"><Lines>{note}</Lines></p>
      </div>
      <div className="shrink-0 text-right">
        <p className="text-[11px] text-muted">도착 예정</p>
        <p className="text-2xl font-medium leading-tight tabular">{arrive ? hm(arrive) : DASH}</p>
      </div>
    </div>
  );
}

export function Stats({ items }: { items: { k: string; v: React.ReactNode; sub?: string }[] }) {
  return (
    <dl className="mt-6 grid grid-cols-3 divide-x divide-line rounded-sm bg-mist py-3">
      {items.map((it) => (
        <div key={it.k} className="px-3 text-center">
          <dt className="text-[11px] text-muted">{it.k}</dt>
          <dd className="mt-1 text-[15px] font-medium tabular text-ink">{it.v}</dd>
          {it.sub && <dd className="text-[11px] text-muted">{it.sub}</dd>}
        </div>
      ))}
    </dl>
  );
}

export interface Segment { label: string; min: number; className: string }

/**
 * 시간 구성 막대 — 두 카드가 같은 축(max)을 써서 막대 길이로 바로 비교된다.
 * 색만으로 구분하지 않도록 아래에 항목·분을 글자로 함께 적는다.
 */
export function TimeBar({ segments, max }: { segments: Segment[]; max: number }) {
  const seg = segments.filter((x) => x.min > 0);
  const total = seg.reduce((t, x) => t + x.min, 0);
  if (!total || !max) return null;
  return (
    <div className="mt-6">
      <div className="flex h-3 w-full gap-[2px]" role="img"
           aria-label={`시간 구성: ${seg.map((x) => `${x.label} ${Math.round(x.min)}분`).join(", ")}`}>
        {seg.map((x) => (
          <span key={x.label} className={`h-full first:rounded-l last:rounded-r ${x.className}`}
                style={{ width: `${(x.min / max) * 100}%` }} title={`${x.label} ${Math.round(x.min)}분`} />
        ))}
      </div>
      <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[12px] text-ink2">
        {seg.map((x) => (
          <li key={x.label} className="flex items-center gap-1.5">
            <span className={`h-2 w-2 rounded-xs ${x.className}`} aria-hidden />{x.label} <span className="tabular text-muted">{durMin(Math.round(x.min))}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function Dot({ tone }: { tone: "ink" | "rail" | "muted" }) {
  const c = tone === "rail" ? "bg-rail" : tone === "ink" ? "bg-ink" : "bg-white ring-faint";
  return <span className={`relative z-10 mt-[5px] h-2.5 w-2.5 flex-none rounded-full ring-2 ${tone === "muted" ? "" : "ring-white"} ${c}`} aria-hidden />;
}

export function Step({ time, tone, children, last = false }: { time: string; tone: "ink" | "rail" | "muted"; children: React.ReactNode; last?: boolean }) {
  return (
    <li className="grid grid-cols-[44px_10px_1fr] gap-x-3">
      <span className="pt-px text-right text-[13px] font-medium tabular text-ink">{time}</span>
      <span className="relative flex justify-center">
        {!last && <span className="absolute left-1/2 top-3 bottom-[-4px] w-px -translate-x-1/2 bg-line" aria-hidden />}
        <Dot tone={tone} />
      </span>
      <div className={`min-w-0 text-sm ${last ? "" : "pb-4"}`}>{children}</div>
    </li>
  );
}

export function Fold({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <details className="group border-t border-line">
      <summary className="flex cursor-pointer list-none items-center justify-between py-3 text-sm font-medium text-ink hover:text-ink2">
        {title}<span className="text-xs text-muted transition group-open:rotate-180" aria-hidden>▾</span>
      </summary>
      <div className="pb-4">{children}</div>
    </details>
  );
}
