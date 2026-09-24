import Link from "next/link";
import { useRouter } from "next/router";
import { useEffect, useState } from "react";

const LINKS = [
  { href: "/", label: "판단" },
  { href: "/road/SEL-DJN", label: "도로 분석", match: "/road" },
  { href: "/rail/SEL-DJN", label: "철도 분석", match: "/rail" },
  { href: "/forecast", label: "예측 성능" },
  { href: "/ops", label: "수집 상태" },
];

/** 테슬라 홈페이지형 상단 바 — 히어로 위에서는 투명, 스크롤하면 흰 바탕 */
export default function Nav({ overlay = false }: { overlay?: boolean }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const on = () => setScrolled(window.scrollY > 24);
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, []);
  useEffect(() => setOpen(false), [router.asPath]);
  const solid = !overlay || scrolled;
  const active = (l: (typeof LINKS)[number]) =>
    l.match ? router.pathname.startsWith(l.match) : router.pathname === l.href;

  return (
    <>
      <header className={`fixed inset-x-0 top-0 z-40 transition-colors duration-300 ${solid ? "bg-white/95 backdrop-blur" : "bg-transparent"}`}>
        <div className="mx-auto flex h-14 max-w-[1600px] items-center justify-between px-4 sm:px-8">
          <Link href="/" className="text-[15px] font-semibold tracking-brand text-ink" aria-label="RoadRail 홈">
            ROADRAIL
          </Link>
          <nav className="hidden lg:flex items-center gap-1" aria-label="주요 메뉴">
            {LINKS.map((l) => (
              <Link key={l.href} href={l.href} className={`nav-link ${active(l) ? "bg-black/5" : ""}`}>{l.label}</Link>
            ))}
          </nav>
          <div className="flex items-center gap-1">
            <a href="/docs" className="nav-link hidden lg:inline-flex">API</a>
            <button className="nav-link lg:hidden" onClick={() => setOpen(true)} aria-expanded={open}>메뉴</button>
          </div>
        </div>
      </header>
      {open && (
        <div className="fixed inset-0 z-50 lg:hidden" role="dialog" aria-modal="true">
          <div className="absolute inset-0 bg-black/30 backdrop-blur-sm" onClick={() => setOpen(false)} />
          <div className="absolute right-0 top-0 h-full w-[300px] bg-white px-6 pt-5 shadow-xl">
            <div className="flex justify-end">
              <button className="nav-link" onClick={() => setOpen(false)} aria-label="닫기">✕</button>
            </div>
            <ul className="mt-4 space-y-1">
              {LINKS.map((l) => (
                <li key={l.href}>
                  <Link href={l.href} className={`block rounded px-4 py-3 text-[15px] font-medium hover:bg-black/5 ${active(l) ? "bg-black/5" : ""}`}>
                    {l.label}
                  </Link>
                </li>
              ))}
              <li><a href="/docs" className="block rounded px-4 py-3 text-[15px] font-medium hover:bg-black/5">API 문서</a></li>
            </ul>
          </div>
        </div>
      )}
    </>
  );
}
