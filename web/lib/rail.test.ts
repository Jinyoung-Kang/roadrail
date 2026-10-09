import assert from "node:assert/strict";
import { test } from "node:test";
import { bandRows, bandText, dowBars, hourBars, mostlyUnplanned, timetablePending, trainsOverThreshold } from "./rail.ts";
import type { Punctuality, Trains } from "./types";

const item = (key: string, onTimeRate: number | null) => ({ key, onTimeRate, samples: 1, verified: 1, avgArrDelayMin: 1,
  p90ArrDelayMin: 1, avgRideMin: 1, meta: null, grade: null, delayBands: { verified: 0, ge20: 0, ge40: 0, ge60: 0, ge90: 0, ge120: 0 } });

test("요일 · 시간대 막대 — 정시율은 %, 모르면 null(0 으로 그리지 않음), 공휴일은 '공휴일'", () => {
  assert.deepEqual(dowBars([item("1", 0.9), item("H", null)]).map((b) => [b.label, b.rate]), [["월", 90], ["공휴일", null]]);
  assert.deepEqual(hourBars([item("07", 0.5)]).map((b) => [b.label, b.rate]), [["7", 50]]);
});

test("운행표가 계획 시각을 모르는 날 · 시간표 받는 중", () => {
  const t = (delays: (number | null)[]) => ({ trains: delays.map((d) => ({ arrDelayMin: d })) } as unknown as Trains);
  assert.equal(mostlyUnplanned(t([null, null, 3])), true);
  assert.equal(mostlyUnplanned(t([1, null, 3])), false);
  assert.equal(mostlyUnplanned(t([])), false);
  assert.equal(mostlyUnplanned(null), false);
  assert.equal(timetablePending(null, { timetablePending: true } as Punctuality), true);
  assert.equal(timetablePending(null, null), false);
});

const bands = (verified: number, ge20: number, ge60 = 0) => ({ verified, ge20, ge40: ge20, ge60, ge90: 0, ge120: 0 });

test("배상 기준 문구 — 횟수가 먼저, 15회 미만은 퍼센트 없음, 검증 운행이 없으면 모름(null)", () => {
  assert.equal(bandText(bands(72, 1)), "1/72 · 1.4%");
  assert.equal(bandText(bands(12, 0)), "0/12");
  assert.equal(bandText(bands(0, 0)), null);
  assert.equal(bandText(null), null);
  assert.equal(bandText(bands(20, 2, 1), 60), "1/20 · 5.0%");
  assert.deepEqual(bandRows(bands(72, 1), null).map((r) => [r.threshold, r.pair, r.nation]).slice(0, 2),
    [[20, "1/72 · 1.4%", null], [40, "1/72 · 1.4%", null]]);
});

test("20분 이상이 있었던 열차만 — 많은 순, 같으면 표본이 많은 순", () => {
  const it = (key: string, verified: number, ge20: number) => ({ ...item(key, 1), delayBands: bands(verified, ge20) });
  assert.deepEqual(trainsOverThreshold([it("a", 30, 0), it("b", 20, 1), it("c", 40, 1), it("d", 10, 2)]).map((i) => i.key), ["d", "c", "b"]);
});
