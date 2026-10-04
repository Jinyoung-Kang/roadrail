import { useEffect, useState } from "react";

const KEY = "rr-admin";

/**
 * 관리 토큰 — 기본은 메모리에만. '이 탭에서 기억'을 켤 때만 sessionStorage(같은 출처 스크립트가 읽을 수 있으므로).
 * 저장소가 막힌 환경(사생활 보호 창 등)에서는 조용히 메모리로만.
 */
export function useAdminToken() {
  const [token, setTokenState] = useState("");
  const [remember, setRememberState] = useState(false);
  useEffect(() => {
    try {
      const saved = sessionStorage.getItem(KEY);
      if (saved) { setTokenState(saved); setRememberState(true); }
    } catch { /* 저장소 없음 */ }
  }, []);
  const save = (v: string, keep: boolean) => {
    setTokenState(v);
    try { if (keep && v) sessionStorage.setItem(KEY, v); else sessionStorage.removeItem(KEY); } catch { /* 무시 */ }
  };
  return {
    token,
    remember,
    setToken: (v: string) => save(v, remember),
    setRemember: (keep: boolean) => { setRememberState(keep); save(token, keep); },
    clear: () => save("", false),
  };
}
