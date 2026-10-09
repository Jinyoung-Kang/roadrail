import { useEffect, useRef, useState } from "react";
import Layout from "@/components/Layout";
import CopyButton from "@/components/ops/CopyButton";
import FailureLog from "@/components/ops/FailureLog";
import { ErrorBox, Loading, Note, PageHero, Section, Spec, SpecStrip, StatusBadge } from "@/components/ui";
import { api, errorText, runJob } from "@/lib/api/client";
import { DASH, mdhm, num, pct } from "@/lib/format";
import { useAdminToken } from "@/lib/hooks/useAdminToken";
import { useApi } from "@/lib/hooks/useApi";
import { useDebounced } from "@/lib/hooks/useDebounced";
import { failuresText, PROVIDER, quotaShares, staleText, summarize } from "@/lib/ops";
import type { OpsStatus } from "@/lib/types";

export default function Ops() {
  const s = useApi<OpsStatus>(api.opsStatus(), 30_000);
  const { token, remember, setToken, setRemember, clear } = useAdminToken();
  const [msg, setMsg] = useState<string | null>(null);
  // 오류 상세는 관리 토큰이 있을 때만 관리 경로에서 — 공개 경로는 상세를 비운다(ADR-028). 틀린 토큰이어도 공개 화면은 그대로
  const key = useDebounced(token, 600);
  const full = useApi<OpsStatus>(key ? api.adminOpsStatus() : null, { refreshMs: 30_000, init: { headers: { "X-Admin-Token": key } } });
  const prevKey = useRef(key);
  const reloadFull = full.reload;
  useEffect(() => {  // 토큰만 바뀌고 주소는 같을 때(이미 관리 경로) 새 토큰으로 다시
    if (prevKey.current && key && prevKey.current !== key) reloadFull();
    prevKey.current = key;
  }, [key, reloadFull]);

  async function run(job: string) {
    setMsg(null);
    try {
      const r = await runJob(job, token);
      setMsg(`${job} 실행 요청 (${r.requestId}) — 수집기가 곧 실행합니다.`);
      setTimeout(s.reload, 3000);
    } catch (e) {
      setMsg(errorText(e));
    }
  }

  const d = full.data ?? s.data;
  const detailed = !!d?.detailed;
  const { road, lag, warnJobs, failures, unresolved: open, latestFailure: failed, stale } = summarize(d);

  return (
    <Layout title="수집 상태">
      <PageHero eyebrow="운영 · FR-701" title="수집 상태"
                sub={d ? <>수집기 {d.collectorAlive ? <><span className="text-good">●</span> 동작 중</> : <><span className="text-crit">✕</span> 응답 없음</>} · heartbeat {mdhm(d.collectorHeartbeat)} · 30초마다 갱신</> : "불러오는 중"}>
        <SpecStrip>
          <Spec value={road?.completeness24h == null ? DASH : (road.completeness24h * 100).toFixed(1)} unit={road?.completeness24h == null ? "" : "%"} label="고속도로 24h 슬롯 완전성 (목표 ≥95%)" />
          <Spec value={lag?.medianMin == null ? DASH : String(Math.round(lag.medianMin))} unit="분" label="도로공사 공개 지연 (중앙값)" />
          <Spec value={d ? String(d.volumes.calls_24h ?? DASH) : DASH} unit="건" label="외부 호출 (24h)" />
          <Spec value={d ? String(warnJobs) : DASH} unit="개" label="경고 작업" />
        </SpecStrip>
      </PageHero>

      <Section eyebrow="작업" title="작업별 최근 실행" wide>
        <ErrorBox error={s.error} />
        {stale.length > 0 && (
          <div role="alert" className="tile mb-6 border-l-4 border-serious p-5">
            <p className="font-medium"><span className="text-serious" aria-hidden>! </span>원천이 새 값을 주지 않습니다 — 작업은 정상으로 끝나도 저장된 행이 없습니다</p>
            <ul className="mt-2 space-y-1 text-sm text-ink2">{stale.map((f) => <li key={f.series}>{staleText(f)}</li>)}</ul>
            <p className="mt-2 text-xs text-muted">원천(공공데이터)이 빈 응답을 주는 동안은 다시 받을 수 없습니다. 원천이 재개되면 그날 값부터 다시 쌓입니다.</p>
          </div>
        )}
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
                    <td className="td text-right">{j.gaps24h ?? DASH}
                      {j.noSamples24h ? <span className="block text-[11px] text-muted" title="원천에 표본이 없는 슬롯 — 전체를 다시 받아도 비어 있어 결측 · 완전성에서 뺌">원천 없음 {j.noSamples24h}</span> : null}</td>
                    <td className="td max-w-[280px] text-xs text-muted">
                      <div className="truncate whitespace-nowrap" title={j.lastMessage ?? ""}>{j.lastMessage ?? ""}</div>
                      {failed[j.job] && <a href={`#run-${failed[j.job]}`} className="font-medium text-accent hover:underline">
                        {j.lastStatus === "OK" ? "지난 24시간 오류 보기 ↓" : "오류 상세 보기 ↓"}</a>}
                    </td>
                    <td className="td"><button className="rounded-sm px-2 py-1 text-xs font-medium text-accent hover:bg-accent/10 disabled:text-faint"
                                              disabled={!token || j.running} onClick={() => run(j.job)}>실행</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="mt-4 flex flex-wrap items-center gap-3 text-sm">
          <label className="flex min-w-0 max-w-full items-center gap-2">
            <span className="shrink-0 text-muted">관리 토큰</span>
            <input type="password" value={token} onChange={(e) => setToken(e.target.value)} placeholder="X-Admin-Token (.env ADMIN_TOKEN)"
                   autoComplete="off" spellCheck={false}
                   className="h-8 w-72 min-w-0 max-w-full rounded-sm bg-cloud px-3 text-sm focus:outline-hidden focus:ring-2 focus:ring-accent" />
          </label>
          <label className="flex items-center gap-1.5 text-xs text-muted">
            <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
            이 탭에서 기억
          </label>
          {token && <button type="button" onClick={clear} className="text-xs text-muted hover:underline">지우기</button>}
          {msg && <span className="text-ink2" role="status">{msg}</span>}
          {key && full.error && <span className="text-crit" role="status">오류 상세를 불러오지 못했습니다 — {errorText(full.error)}</span>}
        </div>
        <Note>관리 토큰을 넣으면 오류 상세(실패 메시지 · 스택 트레이스 · 외부 호출)도 보입니다. 토큰은 기본으로 이 화면의 메모리에만 두고, '이 탭에서 기억'을 켜면 이 탭의 sessionStorage 에 둡니다. 실행 요청은 Redis Stream(rr:commands) 을 거쳐 수집기가 처리하며, 실행 중이면 409 JOB_RUNNING 입니다.</Note>
      </Section>

      {d && (
        <Section id="failures" eyebrow="오류 상세" wide
                 title={failures.length ? `최근 24시간 오류 ${failures.length}건${open ? ` · 해결 안 됨 ${open}건` : " · 모두 이후 정상"}` : "최근 24시간 오류 없음"}
                 desc="실패 · 부분 성공 · 예산 부족으로 끝난 실행의 전체 내용입니다. 해결 안 된 것부터 보이며, 같은 작업이 그 뒤 정상 종료했으면 '이후 정상'으로 표시합니다.">
          {failures.length === 0 ? <p className="text-center text-sm text-muted"><span className="text-good">●</span> 모든 작업이 정상 종료했습니다.</p> : (
            <>
              <div className="mb-4 flex items-center justify-end gap-3">
                {detailed ? <CopyButton text={failuresText(failures)} label={`전체 ${failures.length}건 복사`} />
                  : <span className="text-sm text-muted">오류 메시지 · 스택 트레이스는 위의 관리 토큰을 넣으면 보입니다.</span>}
              </div>
              <div className="space-y-3">{failures.map((f, i) => <FailureLog key={f.runId} f={f} detailed={detailed} open={detailed && i === 0 && !f.resolvedAt} />)}</div>
            </>
          )}
        </Section>
      )}

      <Section eyebrow="예산" title="공급자별 오늘 호출 예산" gray wide desc="수집기와 API(출발지→도착지 조회 시점 호출)가 같은 Redis 예산을 원자적으로 예약합니다(Lua). 예산이 모자라면 호출하지 않습니다.">
        {d && (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {d.quota.map((q) => {
              const { used, reserved: res } = quotaShares(q);
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
                    <li key={i} className="text-ink2"><span className="text-crit">✕</span> {mdhm(e.calledAt)} · {PROVIDER[e.provider] ?? e.provider}{e.endpoint ? ` ${e.endpoint}` : ""} · HTTP {e.httpStatus ?? DASH}{e.error ? ` · ${e.error}` : ""}</li>
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
