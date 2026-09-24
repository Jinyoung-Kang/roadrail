import { useEffect, useState } from "react";
import Layout from "@/components/Layout";
import { ErrorBox, Loading, Note, PageHero, Section, Spec, SpecStrip, StatusBadge } from "@/components/ui";
import { getJson, HttpError, useApi } from "@/lib/api";
import { DASH, mdhm, num, pct } from "@/lib/format";
import type { OpsStatus } from "@/lib/types";

const PROVIDER: Record<string, string> = { EX: "한국도로공사", KORAIL: "한국철도공사", KMA: "기상청", AIRKOREA: "에어코리아", KAKAO: "카카오", "-": "내부 계산" };

export default function Ops() {
  const s = useApi<OpsStatus>("/api/v1/ops/collect-status", 30_000);
  const [token, setToken] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => { try { setToken(sessionStorage.getItem("rr-admin") ?? ""); } catch { /* 저장소 없음 */ } }, []);
  const saveToken = (v: string) => { setToken(v); try { sessionStorage.setItem("rr-admin", v); } catch { /* 무시 */ } };

  async function run(job: string) {
    setMsg(null);
    try {
      const r = await getJson<{ requestId: string }>(`/api/v1/admin/jobs/${job}/run`, { method: "POST", headers: { "X-Admin-Token": token } });
      setMsg(`${job} 실행 요청 (${r.requestId}) — 수집기가 곧 실행합니다.`);
      setTimeout(s.reload, 3000);
    } catch (e) {
      setMsg(e instanceof HttpError ? `${e.body?.code ?? e.status}: ${e.message}` : String(e));
    }
  }

  const d = s.data;
  const road = d?.jobs.find((j) => j.job === "road_travel_time");
  const lag = d?.publicationLag.find((l) => l.series === "road_travel_time");
  const warnJobs = d?.jobs.filter((j) => j.warn).length ?? 0;

  return (
    <Layout title="수집 상태">
      <PageHero eyebrow="운영 · FR-701" title="수집 상태"
                sub={d ? <>수집기 {d.collectorAlive ? <><span className="text-good">●</span> 동작 중</> : <><span className="text-crit">✕</span> 응답 없음</>} · heartbeat {mdhm(d.collectorHeartbeat)} · 30초마다 갱신</> : "불러오는 중"}>
        <SpecStrip>
          <Spec value={road?.completeness24h == null ? DASH : (road.completeness24h * 100).toFixed(1)} unit={road?.completeness24h == null ? "" : "%"} label="도로 24h 슬롯 완전성 (목표 ≥95%)" />
          <Spec value={lag?.medianMin == null ? DASH : String(Math.round(lag.medianMin))} unit="분" label="도로공사 공개 지연 (중앙값)" />
          <Spec value={d ? String(d.volumes.calls_24h ?? DASH) : DASH} unit="건" label="외부 호출 (24h)" />
          <Spec value={d ? String(warnJobs) : DASH} unit="개" label="경고 작업" />
        </SpecStrip>
      </PageHero>

      <Section eyebrow="작업" title="작업별 최근 실행" wide>
        <ErrorBox error={s.error} />
        {!d && s.loading && <Loading />}
        {d && (
          <div className="tile overflow-x-auto">
            <table className="w-full">
              <thead><tr>
                <th className="th">작업</th><th className="th">공급자</th><th className="th">주기(cron)</th><th className="th">상태</th><th className="th">최근 실행</th>
                <th className="th text-right">호출</th><th className="th text-right">행</th><th className="th text-right">24h 완전성</th><th className="th text-right">결측</th><th className="th">메시지</th><th className="th" />
              </tr></thead>
              <tbody>
                {d.jobs.map((j) => (
                  <tr key={j.job} className={j.warn ? "bg-[#fff8f3]" : "hover:bg-mist"}>
                    <td className="td"><div className="font-medium">{j.job}</div><div className="text-xs text-muted">{j.description}</div></td>
                    <td className="td">{PROVIDER[j.provider] ?? j.provider}</td>
                    <td className="td font-mono text-xs">{j.cron}</td>
                    <td className="td"><StatusBadge status={j.running ? "RUNNING" : j.lastStatus} /></td>
                    <td className="td">{mdhm(j.lastRunAt)}<div className="text-xs text-muted">{j.lastDurationMs == null ? "" : `${num(j.lastDurationMs / 1000)}초`}</div></td>
                    <td className="td text-right">{j.lastCalls ?? DASH}</td>
                    <td className="td text-right">{j.lastRows?.toLocaleString() ?? DASH}</td>
                    <td className="td text-right">{j.completeness24h == null ? DASH : (
                      <span className="inline-flex items-center gap-2">
                        {j.completeness24h < 0.95 && <span className="text-serious" title="95% 미만">▲</span>}{pct(j.completeness24h, 1)}
                      </span>)}</td>
                    <td className="td text-right">{j.gaps24h ?? DASH}</td>
                    <td className="td max-w-[280px] truncate whitespace-nowrap text-xs text-muted" title={j.lastMessage ?? ""}>{j.lastMessage ?? ""}</td>
                    <td className="td"><button className="rounded px-2 py-1 text-xs font-medium text-accent hover:bg-accent/10 disabled:text-faint"
                                              disabled={!token || j.running} onClick={() => run(j.job)}>실행</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="mt-4 flex flex-wrap items-center gap-3 text-sm">
          <label className="flex items-center gap-2">
            <span className="text-muted">관리 토큰</span>
            <input type="password" value={token} onChange={(e) => saveToken(e.target.value)} placeholder="X-Admin-Token (.env ADMIN_TOKEN)"
                   className="h-8 w-72 rounded bg-cloud px-3 text-sm focus:outline-none focus:ring-2 focus:ring-accent" />
          </label>
          {msg && <span className="text-ink2" role="status">{msg}</span>}
        </div>
        <Note>토큰은 이 브라우저 탭의 sessionStorage 에만 둡니다. 실행 요청은 Redis Stream(rr:commands) 을 거쳐 수집기가 처리하며, 실행 중이면 409 JOB_RUNNING 입니다.</Note>
      </Section>

      <Section eyebrow="예산" title="공급자별 오늘 호출 예산" gray wide desc="작업은 시작할 때 예상 호출 수를 원자적으로 예약하고(Redis Lua), 남으면 환불합니다. 예산이 모자라면 호출 없이 SKIPPED_QUOTA 로 끝납니다.">
        {d && (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            {d.quota.map((q) => {
              const used = q.limit ? q.used / q.limit : 0, res = q.limit ? q.reserved / q.limit : 0;
              return (
                <div key={q.provider} className="tile p-5">
                  <p className="text-sm font-medium">{PROVIDER[q.provider]}</p>
                  <p className="mt-2 text-2xl font-medium tabular">{q.used.toLocaleString()}<span className="text-sm text-muted"> / {q.limit.toLocaleString()}</span></p>
                  <div className="mt-3 flex h-2 overflow-hidden rounded-full bg-cloud" role="img" aria-label={`사용 ${pct(used)} · 예약 ${pct(res)}`}>
                    <span className="h-full bg-ink" style={{ width: `${Math.min(used, 1) * 100}%` }} />
                    <span className="h-full bg-faint" style={{ width: `${Math.min(res, 1) * 100}%`, marginLeft: 2 }} />
                  </div>
                  <p className="mt-2 text-xs text-muted">사용 {pct(used, 1)} · 예약 중 {q.reserved} · 남음 {q.remaining.toLocaleString()}</p>
                </div>
              );
            })}
          </div>
        )}
      </Section>

      <Section eyebrow="관측성" title="실행 이력 · 오류 · 데이터 규모" wide>
        {d && (
          <div className="grid gap-6 lg:grid-cols-2">
            <div className="tile overflow-x-auto">
              <table className="w-full">
                <thead><tr><th className="th">시작</th><th className="th">작업</th><th className="th">트리거</th><th className="th">상태</th><th className="th text-right">호출</th><th className="th text-right">행</th></tr></thead>
                <tbody>{d.recentRuns.map((r) => (
                  <tr key={r.runId}><td className="td">{mdhm(r.startedAt)}</td><td className="td">{r.job}</td><td className="td text-xs text-muted">{r.trigger}</td>
                    <td className="td"><StatusBadge status={r.status} /></td><td className="td text-right">{r.calls}</td><td className="td text-right">{r.rows.toLocaleString()}</td></tr>
                ))}</tbody>
              </table>
            </div>
            <div className="space-y-6">
              <div className="tile p-5">
                <p className="text-sm font-medium">최근 24시간 외부 호출 오류</p>
                {d.recentErrors.length === 0 ? <p className="mt-2 text-sm text-muted"><span className="text-good">●</span> 오류 없음</p> : (
                  <ul className="mt-2 space-y-2 text-xs">{d.recentErrors.map((e, i) => (
                    <li key={i} className="text-ink2"><span className="text-crit">✕</span> {mdhm(e.calledAt)} · {e.provider} {e.endpoint} · HTTP {e.httpStatus ?? DASH} · {e.error}</li>
                  ))}</ul>
                )}
              </div>
              <div className="tile p-5">
                <p className="text-sm font-medium">공개 지연 (원본 첫 저장 − 슬롯 시각, 24h)</p>
                <ul className="mt-2 text-sm text-ink2">{d.publicationLag.map((l) => (
                  <li key={l.series} className="flex justify-between border-b border-line py-2 last:border-0"><span>{l.series}</span><span className="tabular">중앙값 {num(l.medianMin, 0)}분 · p90 {num(l.p90Min, 0)}분 · n={l.n.toLocaleString()}</span></li>
                ))}</ul>
              </div>
              <div className="tile p-5">
                <p className="text-sm font-medium">저장 규모</p>
                <dl className="mt-2 grid grid-cols-2 gap-x-6 text-sm">{Object.entries(d.volumes).map(([k, v]) => (
                  <div key={k} className="flex justify-between border-b border-line py-1.5"><dt className="text-muted">{k}</dt><dd className="tabular">{typeof v === "number" ? v.toLocaleString() : v ?? DASH}</dd></div>
                ))}</dl>
              </div>
              {d.backfills.length > 0 && (
                <div className="tile p-5">
                  <p className="text-sm font-medium">백필</p>
                  <ul className="mt-2 text-sm text-ink2">{d.backfills.map((b) => (
                    <li key={b.backfillId} className="flex justify-between border-b border-line py-2 last:border-0">
                      <span>{b.provider} {b.from} ~ {b.to}</span><span className="flex items-center gap-3 tabular">{b.doneDays}일 · 예상 {b.plannedCalls}건 <StatusBadge status={b.status} /></span>
                    </li>
                  ))}</ul>
                </div>
              )}
            </div>
          </div>
        )}
      </Section>
    </Layout>
  );
}
