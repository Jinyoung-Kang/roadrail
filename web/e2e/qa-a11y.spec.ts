import { expect, test, type Page } from "@playwright/test";

// QA 접근성 — WCAG 2.1 AA 중 자동으로 확인할 수 있는 것. 개발 스택(실데이터 · 카카오 경로)에서 실행: E2E_CHANNEL=chrome npx playwright test qa-a11y

/** 보이는 글자의 대비(1.4.3) — 글자 색과 가장 가까운 불투명 배경색. 배경 그림 · 그라데이션 위 글자는 건너뛴다 */
async function lowContrastText(page: Page) {
  return page.evaluate(() => {
    const rgb = (s: string) => (s.match(/[\d.]+/g) ?? []).map(Number);
    const lum = ([r, g, b]: number[]) => {
      const f = (c: number) => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
      return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    };
    const out: string[] = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const seen = new Set<Element>();
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      const el = n.parentElement;
      if (!el || seen.has(el) || !(n.textContent ?? "").trim()) continue;
      seen.add(el);
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0 || cs.visibility === "hidden" || el.closest("[aria-hidden=true], button:disabled, svg")) continue;
      let bg: number[] | null = null;
      for (let a: Element | null = el; a; a = a.parentElement) {
        const s = getComputedStyle(a);
        if (s.backgroundImage !== "none") { bg = null; break; }
        const c = rgb(s.backgroundColor);
        if (c.length >= 3 && (c.length < 4 || c[3] === 1)) { bg = c; break; }
      }
      if (!bg) continue;
      const fg = rgb(cs.color);
      if (fg.length >= 4 && fg[3] < 1) continue;   // 반투명 글자는 계산하지 않음
      const L1 = lum(fg), L2 = lum(bg);
      const ratio = (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
      const px = parseFloat(cs.fontSize), bold = Number(cs.fontWeight) >= 700;
      const large = px >= 24 || (bold && px >= 18.66);
      if (ratio < (large ? 3 : 4.5)) out.push(`${ratio.toFixed(2)}:1 ${px}px "${(n.textContent ?? "").trim().slice(0, 20)}" <${el.tagName.toLowerCase()} class="${String(el.className).slice(0, 50)}">`);
    }
    return out;
  });
}

test("QA-03 작은 안내 글자의 대비가 4.5:1 이상 — 고속도로 실측 · 경로 분석", async ({ page }) => {
  // 수정 전: Lighthouse color-contrast — 히트맵 시각 눈금(10px)과 경로 분석의 도로 구분 안내(11px)가 #8e8e8e(흰 바탕 3.3:1)
  for (const path of ["/road/SEL-DJN", "/road"]) {
    await page.goto(path, { waitUntil: "networkidle" });
    await page.waitForTimeout(1500);
    expect(await lowContrastText(page), path).toEqual([]);
  }
});

test("QA-07 320px 폭에서 가로로 넘치지 않는다(1.4.10 리플로) — 수집 상태", async ({ page }) => {
  // 수정 전: 관리 토큰 입력칸(w-72)이 라벨과 함께 320px 를 넘어 4px 가로 스크롤
  await page.setViewportSize({ width: 320, height: 640 });
  await page.goto("/ops", { waitUntil: "networkidle" });
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
});

test("QA-08 라디오 그룹(출발 시점 · 기간 · 정시 기준)은 화살표 키로 옮긴다 — 탭 정지는 하나", async ({ page }) => {
  // 수정 전: role=radiogroup 인데 항목마다 Tab 이 멈추고 화살표 키는 아무 일도 하지 않았다(ARIA 라디오 그룹 방식과 다름)
  await page.goto("/rail", { waitUntil: "networkidle" });
  const group = page.getByRole("radiogroup", { name: "기간" });
  const first = group.getByRole("radio", { name: "최근 30일" });
  await first.focus();
  await page.keyboard.press("ArrowRight");
  await expect(group.getByRole("radio", { name: "최근 90일" })).toBeFocused();
  await expect(group.getByRole("radio", { name: "최근 90일" })).toHaveAttribute("aria-checked", "true");
  expect(await group.getByRole("radio").evaluateAll((els) => els.filter((e) => e.getAttribute("tabindex") !== "-1").length)).toBe(1);
});
