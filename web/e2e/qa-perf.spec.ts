import { expect, test } from "@playwright/test";

// QA 화면 성능 — Core Web Vitals 중 브라우저에서 바로 잴 수 있는 레이아웃 이동(CLS). 개발 스택에서: E2E_CHANNEL=chrome npx playwright test qa-perf
// 기준: CLS < 0.1 '좋음'(web.dev). 사용자 입력 직후의 이동은 빼고 센다.
for (const [vp, w, h] of [["desktop", 1440, 900], ["mobile", 375, 812]] as const) {
  for (const path of ["/road", "/rail", "/road/SEL-DJN", "/"]) {
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
