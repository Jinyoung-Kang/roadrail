import assert from "node:assert/strict";
import { test } from "node:test";
import { dowBars, hourBars, mostlyUnplanned, timetablePending } from "./rail.ts";
import type { Punctuality, Trains } from "./types";

const item = (key: string, onTimeRate: number | null) => ({ key, onTimeRate, samples: 1, verified: 1, avgArrDelayMin: 1,
  p90ArrDelayMin: 1, avgRideMin: 1, meta: null, grade: null });

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
