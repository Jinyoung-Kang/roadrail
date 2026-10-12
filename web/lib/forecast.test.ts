import assert from "node:assert/strict";
import { test } from "node:test";
import { MODELS, modelVersionText } from "./forecast.ts";

test("예측 방법 이름은 영어 · 기호 없는 한글", () => {
  assert.deepEqual(MODELS.map((m) => m.name), ["평소 값", "평소 값 + 지금 차이", "지금 값 그대로"]);
  for (const m of MODELS) assert.doesNotMatch(m.name + m.desc, /[A-Za-z]/);
});

test("규칙 버전 문구", () => {
  assert.equal(modelVersionText("F-v1(tau=90)"), "규칙 F-v1 · 지금 차이가 줄어드는 속도 90분");
  assert.equal(modelVersionText("F-v2"), "규칙 F-v2");
  assert.equal(modelVersionText(null), "");
});
