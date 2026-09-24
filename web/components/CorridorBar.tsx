import { useRouter } from "next/router";
import { Segmented, Select } from "@/components/ui";
import type { Corridor, Dir } from "@/lib/types";

/** 분석 화면 공통: 코리도 · 방향 선택 (URL 로 유지) */
export default function CorridorBar({ base, corridors, cid, dir, children }: {
  base: "road" | "rail"; corridors: Corridor[] | null; cid: string; dir: Dir; children?: React.ReactNode;
}) {
  const router = useRouter();
  const c = corridors?.find((x) => x.id === cid);
  const go = (id: string, d: Dir) => router.push({ pathname: `/${base}/${id}`, query: { dir: d } }, undefined, { scroll: false });
  return (
    <div className="flex flex-wrap items-center justify-center gap-2">
      <Select label="코리도" value={cid} onChange={(v) => go(v, dir)}
              options={(corridors ?? []).map((x) => ({ value: x.id, label: x.name }))} />
      <Segmented label="방향" value={dir} onChange={(d) => go(cid, d)}
                 options={[{ value: "DN" as Dir, label: c ? `${c.originCity}→${c.destCity}` : "하행" },
                           { value: "UP" as Dir, label: c ? `${c.destCity}→${c.originCity}` : "상행" }]} />
      {children}
    </div>
  );
}
