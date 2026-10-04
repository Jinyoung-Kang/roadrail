import assert from "node:assert/strict";
import { test } from "node:test";
import { layersSignature, type MapLayers } from "./map.ts";

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
