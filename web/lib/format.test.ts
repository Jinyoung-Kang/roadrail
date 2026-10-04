import assert from "node:assert/strict";
import { test } from "node:test";
import { daysUntilYesterday, durMin, ymd } from "./format.ts";

test("어제까지 N일 — KST 날짜 기준 (자정 직후도 한국 날짜로)", () => {
  assert.deepEqual(daysUntilYesterday(new Date("2026-10-04T00:30:00+09:00"), 30), { from: "2026-09-04", to: "2026-10-03" });
  assert.deepEqual(daysUntilYesterday(new Date("2026-10-03T15:30:00Z"), 1), { from: "2026-10-03", to: "2026-10-03" });  // = 10-04 00:30 KST
  assert.equal(ymd(new Date("2026-12-31T15:00:00Z")), "2027-01-01");
});

test("분 표기", () => {
  assert.equal(durMin(45), "45분");
  assert.equal(durMin(107), "1시간 47분");
  assert.equal(durMin(null), "—");
});
