import CopyButton from "@/components/ops/CopyButton";
import { StatusBadge } from "@/components/ui";
import { mdhm } from "@/lib/format";
import { failureText } from "@/lib/ops";
import type { OpsFailure } from "@/lib/types";

/** 오류 실행 한 건 — 펼치면 전체 내용(작업 메모 · 실패한 외부 호출 · 스택 트레이스)과 복사 */
export default function FailureLog({ f, open }: { f: OpsFailure; open: boolean }) {
  return (
    <details id={`run-${f.runId}`} open={open} className="tile scroll-mt-24 overflow-hidden">
      <summary className="flex cursor-pointer list-none items-center gap-3 px-5 py-4 hover:bg-mist">
        <StatusBadge status={f.status} />
        <span className="min-w-0 flex-1">
          <span className="block text-sm font-medium">{f.job} <span className="font-normal text-muted">· 실행 #{f.runId} · {f.trigger}</span>
            {f.resolvedAt && <span className="ml-2 rounded-sm bg-[#e8f6ef] px-1.5 py-0.5 text-[11px] font-medium text-ink2"><span className="text-good" aria-hidden>●</span> 이후 정상 실행 {mdhm(f.resolvedAt)}</span>}</span>
          <span className="block truncate text-xs text-muted">{mdhm(f.startedAt)} · {f.message ?? "메시지 없음"}</span>
        </span>
        <span className="text-xs text-muted" aria-hidden>펼치기 ▾</span>
      </summary>
      <div className="border-t border-line bg-[#fafafa]">
        <div className="flex items-center justify-between gap-3 px-5 py-2">
          <span className="text-xs text-muted">전체 내용 · 작업 메모 · 실패한 외부 호출 · 스택 트레이스 (API 키는 가려져 있습니다)</span>
          <CopyButton text={failureText(f)} />
        </div>
        <pre className="max-h-[420px] overflow-auto whitespace-pre-wrap break-all px-5 pb-5 font-mono text-[12px] leading-relaxed text-ink2">{f.detail ?? f.message ?? "기록된 내용이 없습니다"}</pre>
      </div>
    </details>
  );
}
