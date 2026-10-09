import { expect, test } from "@playwright/test";

// QA 화면 성능 — Core Web Vitals 중 브라우저에서 바로 잴 수 있는 레이아웃 이동(CLS). 개발 스택에서: E2E_CHANNEL=chrome npx playwright test qa-perf
// 기준: CLS < 0.1 '좋음'(web.dev). 사용자 입력 직후의 이동은 빼고 센다.
for (const [vp, w, h] of [["desktop", 1440, 900], ["mobile", 375, 812]] as const) {
  for (const path of ["/road", "/rail", "/road/SEL-DJN", "/", "/?by=arrive"]) {
    test(`QA-04 ${vp} ${path} 데이터가 들어와도 화면이 크게 밀리지 않는다 (CLS < 0.1)`, async ({ page }) => {
      // 수정 전: /road 데스크톱 0.263 · 모바일 0.274('나쁨' > 0.25), /rail 모바일 0.141 — 결과가 들어오며 꼬리말 · 본문이 밀림
      await page.setViewportSize({ width: w, height: h });
      await page.addInitScript(() => {
        (window as any).__cls = 0;
        new PerformanceObserver((l) => { for (const e of l.getEntries() as any[]) if (!e.hadRecentInput) (window as any).__cls += e.value; })
          .observe({ type: "layout-shift", buffered: true });
      });
      await page.goto(path, { waitUntil: "networkidle" });
      await page.waitForTimeout(2500);
      expect(await page.evaluate(() => (window as any).__cls)).toBeLessThan(0.1);
    });
  }
}

for (const [vp, w, h] of [["desktop", 1440, 900], ["mobile", 375, 812]] as const) {
  test(`QA-09 ${vp} / 판단 문구가 길어도(비교할 수 없음) 첫 화면이 밀리지 않는다 (CLS < 0.1)`, async ({ page }) => {
    // 수정 전(모바일): 0.139 — '경로 분석' 버튼이 출발지 · 도착지를 읽은 뒤에야 생겨 하단 블록이 52px 커지고(0.075),
    // 두 줄이 된 판단 문구가 출발지 · 도착지 입력을 21px 밀어냄(0.064). 어느 스택에서나 같은 상태가 되도록 응답의 판단만 바꾼다
    await page.setViewportSize({ width: w, height: h });
    await page.route("**/api/v1/trip?*", async (route) => {
      const res = await route.fetch();
      const body = await res.json();
      body.decision = { ...body.decision, verdict: "UNKNOWN", diffMin: null, summary: "비교할 수 없습니다 — 도로·철도 데이터가 부족합니다." };
      await route.fulfill({ response: res, json: body });
    });
    await page.addInitScript(() => {
      (window as any).__cls = 0;
      new PerformanceObserver((l) => { for (const e of l.getEntries() as any[]) if (!e.hadRecentInput) (window as any).__cls += e.value; })
        .observe({ type: "layout-shift", buffered: true });
    });
    await page.goto("/", { waitUntil: "networkidle" });
    await page.waitForTimeout(2500);
    expect(await page.evaluate(() => (window as any).__cls)).toBeLessThan(0.1);
  });
}
