/** 예측 성능 화면의 규칙 — React 에 의존하지 않는다 */
import { MODEL_LABEL } from "./format.ts";

/** 예측 방법 세 가지 — 이름은 MODEL_LABEL, 설명은 한 줄 */
export const MODELS = [
  { key: "M0", name: MODEL_LABEL.M0, desc: "같은 요일 · 같은 시각의 최근 8주 가운데 값" },
  { key: "M1", name: MODEL_LABEL.M1, desc: "지금 평소보다 느리거나 빠른 만큼이 시간이 지나며 줄어든다고 봄" },
  { key: "persistence", name: MODEL_LABEL.persistence, desc: "지금 값이 계속된다고 봄 — 비교용 가장 단순한 방법" },
] as const;

/** 규칙 버전 "F-v1(tau=90)" → "규칙 F-v1 · 지금 차이가 줄어드는 속도 90분" (형식이 다르면 그대로) */
export function modelVersionText(v: string | null | undefined): string {
  if (!v) return "";
  const m = /^(.+?)\(tau=(\d+)\)$/.exec(v);
  return m ? `규칙 ${m[1]} · 지금 차이가 줄어드는 속도 ${m[2]}분` : `규칙 ${v}`;
}
