import { useEffect, useState } from "react";

/** 클립보드 복사 — http 로 연 경우(보안 컨텍스트 아님)에는 textarea 선택 복사로 대신 */
async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) { await navigator.clipboard.writeText(text); return true; }
    const ta = document.createElement("textarea");
    ta.value = text; ta.setAttribute("readonly", ""); ta.style.position = "fixed"; ta.style.opacity = "0";
    document.body.appendChild(ta); ta.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(ta);
    return ok;
  } catch { return false; }
}

export default function CopyButton({ text, label = "복사" }: { text: string; label?: string }) {
  const [state, setState] = useState<"idle" | "ok" | "fail">("idle");
  useEffect(() => { if (state === "idle") return; const t = setTimeout(() => setState("idle"), 1800); return () => clearTimeout(t); }, [state]);
  return (
    <>
      <button type="button" onClick={async () => setState((await copyText(text)) ? "ok" : "fail")}
              className="inline-flex h-8 shrink-0 items-center gap-1.5 rounded-sm bg-white px-3 text-xs font-medium text-ink ring-1 ring-black/10 hover:bg-cloud">
        <span aria-hidden>{state === "ok" ? "✓" : "⧉"}</span>
        {state === "ok" ? "복사됨" : state === "fail" ? "복사 실패" : label}
      </button>
      <span className="sr-only" role="status">{state === "ok" ? "클립보드에 복사했습니다" : state === "fail" ? "복사하지 못했습니다" : ""}</span>
    </>
  );
}
