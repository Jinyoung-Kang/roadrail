import assert from "node:assert/strict";
import { test } from "node:test";
import { sentences, toLines } from "./text.ts";

test("문장 끝(한글 · 닫는 괄호 뒤 마침표)에서 나눈다", () => {
  assert.deepEqual(sentences("전국을 검색해 고릅니다. 출발 시점(지금 ~ 3시간 뒤)도 정할 수 있습니다."),
    ["전국을 검색해 고릅니다.", "출발 시점(지금 ~ 3시간 뒤)도 정할 수 있습니다."]);
  assert.deepEqual(sentences("규칙 F-v1(90분). 다음 문장"), ["규칙 F-v1(90분).", "다음 문장"]);
});

test("날짜 · 소수 · 끝 공백은 쪼개지 않는다", () => {
  assert.deepEqual(sentences("마지막 관측 10. 10. 02:15 기준 0.5분 차이입니다."), ["마지막 관측 10. 10. 02:15 기준 0.5분 차이입니다."]);
  assert.deepEqual(sentences("한 문장뿐 "), ["한 문장뿐"]);
  assert.deepEqual(sentences(""), []);
});

test("끼워 넣은 값 앞뒤에서는 줄을 바꾸지 않고, 문장 끝에서만 바꾼다", () => {
  const v = { value: "07:30" };
  assert.deepEqual(toLines(["마지막 관측 ", v, " 에서 예측합니다. 도로공사가 늦게 공개합니다."]),
    [["마지막 관측 ", v, " 에서 예측합니다."], ["도로공사가 늦게 공개합니다."]]);
  // 문장으로 끝난 조각 뒤의 조각은 새 줄 · 빈 조각은 버림
  assert.deepEqual(toLines(["첫 문장입니다.", " 입력 기간 ", v, ".", ""]), [["첫 문장입니다."], ["입력 기간 ", v, "."]]);
  assert.deepEqual(toLines(["한 줄", " 이어짐"]), [["한 줄", " 이어짐"]]);
});
