import assert from "node:assert/strict";
import { test } from "node:test";
import { frameSignature, layersSignature, type MapLayers } from "./map.ts";

const route: MapLayers = {
  lines: [{ path: [[37.55, 126.97], [36.33, 127.43]], color: "#2a78d6" }],
  markers: [{ lat: 37.55, lon: 126.97, label: "서울역", color: "#171a20" }, { lat: 36.33, lon: 127.43, label: "대전역", color: "#171a20" }],
};

test("같은 내용이면 같은 서명, 없으면 빈 서명", () => {
  assert.equal(layersSignature(structuredClone(route)), layersSignature(route));
  assert.equal(layersSignature(null), "");
});

test("끝점이 바뀌면 서명이 바뀐다", () => {
  const moved = structuredClone(route);
  moved.markers[1] = { ...moved.markers[1], lat: 35.11, lon: 129.04, label: "부산역" };
  assert.notEqual(layersSignature(moved), layersSignature(route));
});

test("색만 바뀌어도(지체 → 정체) 서명이 바뀐다 — 지도에 새 색을 그린다 (WEB-03)", () => {
  // 서명이 JSON 길이 기반이라, 같은 길이의 색으로 바뀌면 띠 · 범례는 바뀌어도 지도는 그대로였다
  const a = structuredClone(route), b = structuredClone(route);
  a.lines.push({ path: [[37.0, 127.0], [36.9, 127.1]], color: "#c93a8c" });  // 지체
  b.lines.push({ path: [[37.0, 127.0], [36.9, 127.1]], color: "#9c1033" });  // 정체
  assert.notEqual(layersSignature(a), layersSignature(b));
});

test("돌발 표식 · 소통 색만 바뀌면 지도 틀(맞춤 범위)은 그대로 — 사용자가 옮긴 위치 · 배율을 지킨다 (WEB-03)", () => {
  const withIncident = structuredClone(route);
  withIncident.markers.push({ lat: 36.9, lon: 127.2, label: "사고", color: "#e8a100", warn: true });
  const recolored = structuredClone(route);
  recolored.lines[0].color = "#9c1033";
  assert.equal(frameSignature(withIncident), frameSignature(route));
  assert.equal(frameSignature(recolored), frameSignature(route));
  const moved = structuredClone(route);
  moved.markers[1] = { ...moved.markers[1], lat: 35.11, lon: 129.04 };
  moved.lines[0].path[1] = [35.11, 129.04];
  assert.notEqual(frameSignature(moved), frameSignature(route));
  assert.equal(frameSignature(null), "");
});
