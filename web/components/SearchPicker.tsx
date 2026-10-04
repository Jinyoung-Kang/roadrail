import { useEffect, useId, useRef, useState } from "react";
import { getJson } from "@/lib/api/client";
import { pickerItems, type SearchResult } from "@/lib/picker";

/**
 * 검색형 선택기 — 입력하면 250ms 뒤 검색, ↑↓ Enter Esc 로 고른다.
 * 비어 있을 때는 `suggestions`(예: 역 전체 목록)를 보여 준다 — 없으면 아무것도 띄우지 않는다.
 * suggestions 는 상태로 복사하지 않고 그대로 그린다: 목록을 받기 전에 칸을 눌러도 도착하는 즉시 보인다.
 */
export default function SearchPicker<T>({ label, placeholder, value, onPick, search, suggestions = [], render, keyOf, className = "" }: {
  label: string; placeholder: string; value: string; onPick: (item: T) => void;
  search: (q: string) => string; suggestions?: T[]; render: (item: T) => { title: string; sub?: string | null; badge?: string };
  keyOf: (item: T) => string; className?: string;
}) {
  const [q, setQ] = useState("");
  // 입력 중이 아니면 고른 값을 입력값으로 보인다 — placeholder(대비 3.3:1)가 아니라 본문 글자색 (WEB-13)
  const [editing, setEditing] = useState(false);
  const [open, setOpen] = useState(false);
  const [result, setResult] = useState<SearchResult<T> | null>(null);
  const [active, setActive] = useState(0);
  const box = useRef<HTMLDivElement>(null);
  const listId = useId();

  useEffect(() => {
    if (!open) return;
    const term = q.trim();
    setActive(0);
    if (!term) return;
    let cancelled = false;
    const t = setTimeout(async () => {
      try {
        const r = await getJson<any>(search(term));
        if (!cancelled) setResult({ term, items: Array.isArray(r) ? r : r.items ?? [], failed: false });
      } catch {
        if (!cancelled) setResult({ term, items: [], failed: true });  // 한도 초과 · 서버 오류 — '결과 없음'과 구분
      }
      if (!cancelled) setActive(0);
    }, 250);
    return () => { cancelled = true; clearTimeout(t); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, open]);

  useEffect(() => {
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);

  const { items, status } = pickerItems(q, result, suggestions);
  // 고른 뒤에도 초점은 입력칸에 남는다(Enter · 항목 mousedown 은 초점을 옮기지 않음) → 입력 상태를 유지해 이어서 칠 수 있게
  const pick = (it: T) => { onPick(it); setQ(""); setOpen(false); };
  const optionId = (i: number) => `${listId}-o${i}`;

  return (
    <div ref={box} className={`relative ${className}`}>
      <label className="flex h-11 items-center gap-2 rounded-sm bg-white/85 px-3 ring-1 ring-black/10 backdrop-blur-sm focus-within:ring-2 focus-within:ring-accent">
        <span className="shrink-0 text-[12px] font-medium text-muted">{label}</span>
        <input role="combobox" aria-expanded={open} aria-controls={listId} aria-label={label} aria-autocomplete="list"
               aria-activedescendant={open && items[active] ? optionId(active) : undefined} autoComplete="off"
               className="w-full min-w-0 bg-transparent text-[15px] font-medium text-ink placeholder:text-faint focus:outline-hidden"
               placeholder={value || placeholder} value={editing ? q : value}
               onFocus={() => { setEditing(true); setOpen(true); }}
               onBlur={() => { setEditing(false); setQ(""); }}
               onChange={(e) => { setEditing(true); setQ(e.target.value); setOpen(true); }}
               onKeyDown={(e) => {
                 if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => Math.min(a + 1, items.length - 1)); }
                 else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
                 else if (e.key === "Enter") { e.preventDefault(); if (items[active]) pick(items[active]); }  // 새 결과 전에는 고르지 않는다
                 else if (e.key === "Escape") setOpen(false);
               }} />
        {status === "loading" && <span className="h-3.5 w-3.5 shrink-0 animate-spin rounded-full border-2 border-line border-t-ink" aria-hidden />}
      </label>
      {open && (items.length > 0 || q.trim()) && (
        <ul id={listId} role="listbox" aria-label={`${label} 후보`} aria-busy={status === "loading"}
            onMouseDown={(e) => e.preventDefault()}  // 목록(스크롤 막대 · 안내 줄)을 눌러도 입력칸 초점 · 검색어를 지킨다
            className="absolute left-0 right-0 z-50 mt-1 max-h-[min(22rem,60vh)] overflow-auto overscroll-contain rounded-sm bg-white py-1 text-left shadow-xl ring-1 ring-black/10">
          {status === "loading" && <li className="px-4 py-3 text-sm text-muted">검색 중…</li>}
          {status === "empty" && <li className="px-4 py-3 text-sm text-muted">검색 결과가 없습니다</li>}
          {status === "error" && <li className="px-4 py-3 text-sm text-crit" role="alert">검색하지 못했습니다 — 잠시 뒤 다시 입력해 주세요</li>}
          {items.map((it, i) => {
            const r = render(it);
            return (
              <li key={keyOf(it)} id={optionId(i)} role="option" aria-selected={i === active}
                  className={`flex cursor-pointer items-center justify-between gap-3 px-4 py-2.5 ${i === active ? "bg-cloud" : ""}`}
                  onMouseEnter={() => setActive(i)} onMouseDown={(e) => { e.preventDefault(); pick(it); }}>
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium text-ink">{r.title}</span>
                  {r.sub && <span className="block truncate text-xs text-muted">{r.sub}</span>}
                </span>
                {r.badge && <span className="shrink-0 rounded-sm bg-cloud px-2 py-0.5 text-[11px] text-ink2">{r.badge}</span>}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
