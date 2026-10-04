import { DASH, pm25Label } from "@/lib/format";
import type { EnvPoint } from "@/lib/types";

export function EnvRow({ env }: { env: Record<"origin" | "dest", EnvPoint> }) {
  return (
    <div className="grid gap-6 sm:grid-cols-2">
      {(["origin", "dest"] as const).map((role) => {
        const e = env[role];
        return (
          <div key={role} className="tile p-6 sm:p-8">
            <p className="eyebrow">{role === "origin" ? "출발지" : "도착지"} · {e?.name ?? DASH}</p>
            <div className="mt-4 grid grid-cols-3 gap-4 text-center">
              <div><div className="text-2xl font-medium tabular">{e?.tmp ?? DASH}{e?.tmp != null && "°"}</div><div className="mt-1 text-xs text-muted">{e?.sky ?? "기온"}</div></div>
              <div><div className="text-2xl font-medium tabular">{e?.pop ?? DASH}{e?.pop != null && "%"}</div><div className="mt-1 text-xs text-muted">강수확률 · {e?.pty ?? DASH}</div></div>
              <div><div className="text-2xl font-medium tabular">{e?.pm25 ?? DASH}</div><div className="mt-1 text-xs text-muted">초미세먼지 · {pm25Label(e?.pm25Grade)}</div></div>
            </div>
            {e?.weatherSource && (
              <p className="mt-4 text-center text-[11px] text-muted">
                날씨 근거 · {e.weatherSource}{e.rain ? <> · <span className="text-ink2">1시간 강수 {e.rain}</span></> : null}
              </p>
            )}
          </div>
        );
      })}
    </div>
  );
}
