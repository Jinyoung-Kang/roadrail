import { expect, test, type Page } from "@playwright/test";
import fs from "node:fs";

// QA 순회 — 모든 화면에서 버튼 · 탭 · 선택 상자를 눌러 보고 콘솔 오류 · 실패한 요청 · 중복 요청 · 가로 넘침 · 키보드 초점을 모은다.
//   E2E_BASE_URL=http://localhost:3301 E2E_CHANNEL=chrome npx playwright test qa-crawl   (QA 스택 — 외부 API 키 없음)
// 결과: test-results/qa-crawl.json (화면 · 폭마다)
const PAGES = ["/", "/road", "/road/SEL-DJN", "/rail", "/rail/SEL-DJN", "/forecast", "/ops", "/nope"];
const VIEWPORTS = [{ name: "desktop", width: 1440, height: 900 }, { name: "mobile", width: 375, height: 812 }];
const report: Record<string, unknown>[] = [];

function watch(page: Page) {
  const out = { console: [] as string[], pageErrors: [] as string[], failed: [] as string[], http: [] as string[], requests: [] as { url: string; t: number }[] };
  page.on("console", (m) => { if (m.type() === "error") out.console.push(m.text().slice(0, 200)); });
  page.on("pageerror", (e) => out.pageErrors.push(e.message.slice(0, 200)));
  page.on("requestfailed", (r) => { if (!r.url().includes("kakao") && !r.url().includes("daumcdn")) out.failed.push(`${r.method()} ${r.url()} ${r.failure()?.errorText}`); });
  page.on("response", (r) => { if (r.status() >= 400 && !r.url().endsWith("/nope")) out.http.push(`${r.status()} ${r.request().method()} ${r.url()}`); });
  page.on("request", (r) => { if (r.url().includes("/api/v1/")) out.requests.push({ url: r.url(), t: Date.now() }); });
  return out;
}

/** 같은 주소를 1초 안에 두 번 이상 부른 것 */
function duplicates(reqs: { url: string; t: number }[]) {
  const seen = new Map<string, number[]>();
  for (const r of reqs) seen.set(r.url, [...(seen.get(r.url) ?? []), r.t]);
  const dup: string[] = [];
  for (const [url, ts] of seen) for (let i = 1; i < ts.length; i++) if (ts[i] - ts[i - 1] < 1000) { dup.push(url.replace(/^https?:\/\/[^/]+/, "")); break; }
  return dup;
}

for (const vp of VIEWPORTS) {
  for (const path of PAGES) {
    test(`QA 순회 ${vp.name} ${path}`, async ({ page }) => {
      test.setTimeout(120_000);
      await page.setViewportSize({ width: vp.width, height: vp.height });
      const w = watch(page);
      await page.goto(path, { waitUntil: "networkidle" });
      await page.waitForTimeout(1500);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      // 버튼 · 탭(세그먼트) 누르기 — 바깥으로 나가는 링크 · 관리 실행 · 메뉴 닫기는 빼고
      const clicked: string[] = [];
      const buttons = page.locator("main button:visible, section button:visible");
      const n = Math.min(await buttons.count(), 40);
      for (let i = 0; i < n; i++) {
        const b = buttons.nth(i);
        // 앞 버튼을 누른 뒤 화면이 바뀌어(예: 판단 기준 → 도착 시각) 이 버튼이 사라졌으면 기다리지 않고 건너뛴다
        if (i >= await buttons.count()) break;
        const label = ((await b.getAttribute("aria-label", { timeout: 2000 }).catch(() => null))
          ?? (await b.innerText({ timeout: 2000 }).catch(() => ""))).trim().slice(0, 30);
        if (!label || /실행|지우기|복사/.test(label) || !(await b.isEnabled().catch(() => false))) continue;
        await b.click({ timeout: 3000 }).catch(() => {});
        clicked.push(label);
        await page.waitForTimeout(250);
      }
      // 선택 상자는 옵션을 하나씩
      const selects = page.locator("select:visible");
      for (let i = 0; i < await selects.count(); i++) {
        const opts = await selects.nth(i).locator("option").evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value).slice(0, 4));
        for (const v of opts) { await selects.nth(i).selectOption(v).catch(() => {}); await page.waitForTimeout(250); }
      }
      await page.waitForLoadState("networkidle").catch(() => {});
      // 키보드: Tab 20번 — 초점이 실제로 옮겨 가고 보이는지
      await page.keyboard.press("Escape");
      await page.locator("body").focus().catch(() => {});
      const focus: string[] = [];
      let invisible = 0;
      for (let i = 0; i < 20; i++) {
        await page.keyboard.press("Tab");
        const f = await page.evaluate(() => {
          const el = document.activeElement as HTMLElement | null;
          if (!el || el === document.body) return null;
          const r = el.getBoundingClientRect(), cs = getComputedStyle(el);
          const ring = cs.outlineStyle !== "none" && cs.outlineWidth !== "0px" || cs.boxShadow !== "none";
          return { tag: el.tagName.toLowerCase(), name: (el.getAttribute("aria-label") || el.textContent || "").trim().slice(0, 24), visible: r.width > 0 && r.height > 0, ring };
        });
        if (f) { focus.push(`${f.tag}:${f.name}${f.ring ? "" : "(초점 표시 없음)"}`); if (!f.visible) invisible++; }
      }
      const entry = { viewport: vp.name, path, overflowPx: overflow, clicked: clicked.length, console: w.console, pageErrors: w.pageErrors,
        failedRequests: w.failed, httpErrors: [...new Set(w.http)], duplicateRequests: duplicates(w.requests), apiRequests: w.requests.length,
        focusNoRing: focus.filter((x) => x.includes("초점 표시 없음")), focusInvisible: invisible, focusOrder: focus.slice(0, 12) };
      report.push(entry);
      fs.mkdirSync("test-results", { recursive: true });
      fs.writeFileSync("test-results/qa-crawl.json", JSON.stringify(report, null, 2));
      expect(w.pageErrors, "처리되지 않은 예외").toEqual([]);
    });
  }
}
