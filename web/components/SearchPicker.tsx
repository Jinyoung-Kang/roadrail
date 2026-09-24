import { useEffect, useId, useRef, useState } from "react";
import { getJson } from "@/lib/api";

/**
 * 검색형 선택기 — 입력하면 250ms 뒤 검색, ↑↓ Enter Esc 로 고른다.
 * 비어 있을 때는 `suggestions`(자주 오가는 길의 도시 등)를 보여 준다.
 */
export default function SearchPicker<T>({ label, placeholder, value, onPick, search, suggestions = [], render, keyOf, className = "" }: {
  label: string; placeholder: string; value: string; onPick: (item: T) => void;
  search: (q: string) => string; suggestions?: T[]; render: (item: T) => { title: string; sub?: string | null; badge?: string };
  keyOf: (item: T) => string; className?: string;
}) {
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<T[]>([]);
  const [active, setActive] = useState(0);
  const [loading, setLoading] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const listId = useId();

  useEffect(() => {
    if (!open) return;
    const term = q.trim();
    if (!term) { setItems(suggestions); setActive(0); return; }
    let cancelled = false;
    setLoading(true);
    const t = setTimeout(async () => {
      try {
        const r = await getJson<any>(search(term));
        if (!cancelled) { setItems(Array.isArray(r) ? r : r.items ?? []); setActive(0); }
      } catch { if (!cancelled) setItems([]); }
      finally { if (!cancelled) setLoading(false); }
    }, 250);
    return () => { cancelled = true; clearTimeout(t); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, open]);

  useEffect(() => {
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);

  const pick = (it: T) => { onPick(it); setQ(""); setOpen(false); };

  return (
    <div ref={box} className={`relative ${className}`}>
      <label className="flex h-11 items-center gap-2 rounded bg-white/85 px-3 ring-1 ring-black/10 backdrop-blur focus-within:ring-2 focus-within:ring-accent">
        <span className="shrink-0 text-[12px] font-medium text-muted">{label}</span>
        <input role="combobox" aria-expanded={open} aria-controls={listId} aria-label={label}
               className="w-full min-w-0 bg-transparent text-[15px] font-medium text-ink placeholder:text-faint focus:outline-none"
               placeholder={value || placeholder} value={q}
               onFocus={() => setOpen(true)} onChange={(e) => { setQ(e.target.value); setOpen(true); }}
               onKeyDown={(e) => {
                 if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => Math.min(a + 1, items.length - 1)); }
                 else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
                 else if (e.key === "Enter" && items[active]) { e.preventDefault(); pick(items[active]); }
                 else if (e.key === "Escape") setOpen(false);
               }} />
        {loading && <span className="h-3.5 w-3.5 shrink-0 animate-spin rounded-full border-2 border-line border-t-ink" aria-hidden />}
      </label>
      {open && (items.length > 0 || q.trim()) && (
        <ul id={listId} role="listbox" className="absolute left-0 right-0 z-50 mt-1 max-h-[min(22rem,60vh)] overflow-auto overscroll-contain rounded bg-white py-1 text-left shadow-xl ring-1 ring-black/10">
          {items.length === 0 && !loading && <li className="px-4 py-3 text-sm text-muted">검색 결과가 없습니다</li>}
          {items.map((it, i) => {
            const r = render(it);
            return (
              <li key={keyOf(it)} role="option" aria-selected={i === active}
                  className={`flex cursor-pointer items-center justify-between gap-3 px-4 py-2.5 ${i === active ? "bg-cloud" : ""}`}
                  onMouseEnter={() => setActive(i)} onMouseDown={(e) => { e.preventDefault(); pick(it); }}>
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium text-ink">{r.title}</span>
                  {r.sub && <span className="block truncate text-xs text-muted">{r.sub}</span>}
                </span>
                {r.badge && <span className="shrink-0 rounded bg-cloud px-2 py-0.5 text-[11px] text-ink2">{r.badge}</span>}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
