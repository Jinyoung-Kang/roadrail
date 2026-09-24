import { DASH } from "@/lib/format";

/** 테슬라 모델 페이지의 스펙 숫자 — 큰 숫자 + 작은 설명 */
export function Spec({ value, unit, label, tone = "ink" }: { value: string; unit?: string; label: string; tone?: "ink" | "road" | "rail" }) {
  return (
    <div className="text-center px-3 sm:px-6">
      <div className="flex items-baseline justify-center gap-1">
        <span className={`text-[28px] sm:text-[34px] font-medium leading-none tabular ${value === DASH ? "text-faint" : "text-ink"}`}>{value}</span>
        {unit && <span className="text-sm font-medium text-ink2">{unit}</span>}
      </div>
      <div className="mt-2 flex items-center justify-center gap-1.5 text-[13px] text-muted">
        {tone !== "ink" && <span className={`inline-block h-2 w-2 rounded-full ${tone === "road" ? "bg-road" : "bg-rail"}`} aria-hidden />}
        <span>{label}</span>
      </div>
    </div>
  );
}

export function SpecStrip({ children }: { children: React.ReactNode }) {
  return <div className="flex flex-wrap items-start justify-center gap-y-6 divide-x divide-black/10">{children}</div>;
}

export function Section({ id, eyebrow, title, desc, children, gray = false, wide = false }: {
  id?: string; eyebrow?: string; title?: string; desc?: React.ReactNode; children: React.ReactNode; gray?: boolean; wide?: boolean;
}) {
  return (
    <section id={id} className={`${gray ? "bg-mist" : "bg-white"} py-16 sm:py-24`}>
      <div className={`mx-auto px-4 sm:px-8 ${wide ? "max-w-[1600px]" : "max-w-[1200px]"}`}>
        {(eyebrow || title) && (
          <div className="mb-10 text-center">
            {eyebrow && <p className="eyebrow mb-2">{eyebrow}</p>}
            {title && <h2 className="text-[28px] sm:text-[32px] font-medium text-ink">{title}</h2>}
            {desc && <p className="mt-3 text-sm text-muted max-w-2xl mx-auto leading-relaxed">{desc}</p>}
          </div>
        )}
        {children}
      </div>
    </section>
  );
}

/** 페이지 상단 소형 히어로 (분석 화면용) */
export function PageHero({ eyebrow, title, sub, children }: { eyebrow?: string; title: string; sub?: React.ReactNode; children?: React.ReactNode }) {
  return (
    <div className="relative overflow-hidden bg-gradient-to-b from-[#eef1f4] to-white">
      <div className="mx-auto max-w-[1200px] px-4 sm:px-8 pt-16 sm:pt-20 pb-12 text-center">
        {eyebrow && <p className="eyebrow mb-3">{eyebrow}</p>}
        <h1 className="text-[34px] sm:text-[44px] font-medium tracking-tight text-ink">{title}</h1>
        {sub && <div className="mt-3 text-[15px] text-ink2">{sub}</div>}
        {children && <div className="mt-10">{children}</div>}
      </div>
    </div>
  );
}

export function Segmented<T extends string | number>({ value, options, onChange, label }: {
  value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; label: string;
}) {
  return (
    <div className="inline-flex items-center gap-1" role="radiogroup" aria-label={label}>
      {options.map((o) => (
        <button key={String(o.value)} role="radio" aria-checked={o.value === value}
                className={`chip ${o.value === value ? "chip-on" : ""}`} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Select({ value, onChange, options, label }: {
  value: string; onChange: (v: string) => void; options: { value: string; label: string }[]; label: string;
}) {
  return (
    <label className="relative inline-flex items-center">
      <span className="sr-only">{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)}
              className="h-8 appearance-none rounded bg-white/70 pl-3 pr-8 text-[13px] font-medium text-ink ring-1 ring-black/5 backdrop-blur hover:bg-white focus:outline-none focus:ring-2 focus:ring-accent">
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <span className="pointer-events-none absolute right-2.5 text-[10px] text-muted">▼</span>
    </label>
  );
}

const STATUS: Record<string, { cls: string; icon: string; label: string }> = {
  OK: { cls: "text-good", icon: "●", label: "정상" },
  PARTIAL: { cls: "text-serious", icon: "◐", label: "일부 실패" },
  FAILED: { cls: "text-crit", icon: "✕", label: "실패" },
  SKIPPED_QUOTA: { cls: "text-serious", icon: "‖", label: "예산 부족" },
  RUNNING: { cls: "text-accent", icon: "◌", label: "실행 중" },
  QUEUED: { cls: "text-muted", icon: "…", label: "대기" },
  DONE: { cls: "text-good", icon: "●", label: "완료" },
  STALE_DATA: { cls: "text-serious", icon: "!", label: "오래된 데이터" },
};

/** 상태는 색 + 아이콘 + 글자 (색만으로 전달하지 않음) */
export function StatusBadge({ status }: { status: string | null | undefined }) {
  if (!status) return <span className="text-faint">{DASH}</span>;
  const s = STATUS[status] ?? { cls: "text-muted", icon: "·", label: status };
  return (
    <span className="inline-flex items-center gap-1.5 text-[13px] font-medium">
      <span className={s.cls} aria-hidden>{s.icon}</span>
      <span className="text-ink2">{s.label}</span>
    </span>
  );
}

export function Loading({ label = "불러오는 중" }: { label?: string }) {
  return (
    <div className="flex items-center justify-center py-16 text-sm text-muted" role="status">
      <span className="mr-2 inline-block h-4 w-4 animate-spin rounded-full border-2 border-line border-t-ink" />
      {label}
    </div>
  );
}

export function ErrorBox({ error }: { error: Error | null }) {
  if (!error) return null;
  return (
    <div className="rounded bg-[#fdf1f1] px-4 py-3 text-sm text-ink2" role="alert">
      <span className="text-crit mr-2" aria-hidden>✕</span>{error.message}
    </div>
  );
}

export function Empty({ children }: { children: React.ReactNode }) {
  return <div className="rounded bg-cloud px-4 py-10 text-center text-sm text-muted">{children}</div>;
}

export function Note({ children }: { children: React.ReactNode }) {
  return <p className="mt-4 text-xs leading-relaxed text-muted">{children}</p>;
}
