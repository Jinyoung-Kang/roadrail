import assert from "node:assert/strict";
import { test } from "node:test";
import { arriveByParam, defaultSlot, kstDate, oddsText, parseArrivalQuery, resolveSlot, slotOptions } from "./arrival.ts";

// 2026-10-10 13:07 KST
const NOW = new Date("2026-10-10T04:07:00Z");

test("도착 시각 선택지는 지금 + 30분 ~ 24시간, 5분 단위", () => {
  const today = slotOptions(NOW, 0);
  assert.equal(today[0].value, "1340");                 // 13:37 이후 첫 5분 칸
  assert.equal(today.at(-1)?.value, "2355");
  const tomorrow = slotOptions(NOW, 1);
  assert.equal(tomorrow[0].value, "0000");
  assert.equal(tomorrow.at(-1)?.value, "1305");         // 내일 13:07 까지
  assert.ok(today.every((o) => Number(o.value.slice(2)) % 5 === 0));
});

test("한국 시각 날짜 — UTC 로는 전날인 새벽도 한국 날짜로", () => {
  const dawn = new Date("2026-10-09T16:30:00Z");       // 10-10 01:30 KST
  assert.equal(kstDate(dawn, 0), "2026-10-10");
  assert.equal(arriveByParam(dawn, 1, "0905"), "2026-10-11T09:05");
});

test("기본 도착 시각은 2시간 뒤를 30분 단위로 올림 — 밤이면 내일", () => {
  assert.deepEqual(defaultSlot(NOW), { day: 0, at: "1530" });
  assert.deepEqual(defaultSlot(new Date("2026-10-10T14:10:00Z")), { day: 1, at: "0130" });   // 23:10 KST → 내일 01:30
});

test("주소 매개변수 왕복 — 잘못된 값은 기본값, 지난 시각은 기본 칸으로", () => {
  const q = parseArrivalQuery({ by: "arrive", d: "1", at: "0905", c: "95" });
  assert.deepEqual(q, { mode: "arrive", day: 1, at: "0905", confidence: 95 });
  assert.deepEqual(parseArrivalQuery({ at: "0907", c: "50" }), { mode: "depart", day: 0, at: null, confidence: 90 });
  assert.deepEqual(resolveSlot(NOW, q), { day: 1, at: "0905" });
  assert.deepEqual(resolveSlot(NOW, { ...q, day: 0, at: "1200" }), { day: 0, at: "1530" });   // 이미 지난 시각
});

test("확률 문구 — 15회 이상 퍼센트, 미만은 빈도, 모두 기한 안은 100% 라고 하지 않는다", () => {
  const base = { basis: "", allObservedWithin: false };
  assert.deepEqual(oddsText({ ...base, percent: 92, within: 22, n: 24 }), { main: "92%", sub: "최근 30일 24회 중 22회 기한 안 도착" });
  assert.equal(oddsText({ ...base, percent: null, within: 9, n: 10 }).main, "10회 중 9회");
  const all = oddsText({ ...base, percent: null, within: 24, n: 24, allObservedWithin: true });
  assert.equal(all.main, "24회 모두 기한 안");
  assert.ok(!all.main.includes("100%"));
  assert.equal(oddsText({ ...base, percent: null, within: null, n: 0 }).main, "기록 없음");
  assert.equal(oddsText(null).main, "기록 없음");
});

test("환승 여정은 구간마다 빈도", () => {
  const t = oddsText({ basis: "", allObservedWithin: false, percent: 72, within: null, n: 20 },
    [{ trnNo: "1", kind: "TRANSFER", allowanceMin: 8, within: 18, n: 20 }, { trnNo: "2", kind: "ARRIVAL", allowanceMin: 12, within: 16, n: 20 }]);
  assert.equal(t.main, "72%");
  assert.equal(t.sub, "환승 20회 중 18회 · 도착 20회 중 16회");
});
