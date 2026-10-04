import { useEffect, useState } from "react";

/** 값이 ms 동안 바뀌지 않으면 그 값 — 입력 중인 토큰으로 매 글자마다 요청하지 않게 */
export function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}
