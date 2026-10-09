/** 화면 글의 규칙 — React 에 의존하지 않는다 */

/** 문장 끝 = 한글 · 닫는 괄호 · 따옴표 · % 뒤의 마침표(또는 ! ?). 숫자 뒤 마침표는 아니다(날짜 "10. 10." · 소수 "0.5") */
const END = /(?<=[가-힣)\]"'’”%][.!?])\s+(?=\S)/u;
const ENDS_SENTENCE = /[가-힣)\]"'’”%][.!?]\s*$/u;

/** 설명 문자열을 문장으로 나눈다 */
export function sentences(text: string): string[] {
  return text.split(END).map((s) => s.trim()).filter(Boolean);
}

/**
 * 글 조각(문자열과 그 밖의 요소 — 값 끼워 넣기 등)을 문장마다 한 줄로 묶는다.
 * 조각은 순서대로 이어 붙이고, 문자열 안의 문장 끝에서만 새 줄을 연다 — 끼워 넣은 값 앞뒤에서 줄이 끊기지 않는다.
 */
export function toLines<T>(parts: (string | T)[]): (string | T)[][] {
  const lines: (string | T)[][] = [[]];
  for (const p of parts) {
    if (typeof p !== "string") {
      lines[lines.length - 1].push(p);
      continue;
    }
    const pieces = p.split(END);
    pieces.forEach((piece, i) => {
      if (i > 0) lines.push([]);
      const cur = lines[lines.length - 1];
      const text = cur.length === 0 ? piece.trimStart() : piece;
      if (text) cur.push(text);
    });
    if (ENDS_SENTENCE.test(p) && lines[lines.length - 1].length) lines.push([]);
  }
  return lines.filter((l) => l.some((x) => typeof x !== "string" || x.trim()));
}
