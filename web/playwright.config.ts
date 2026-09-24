import { defineConfig } from "@playwright/test";

// 로컬: E2E_CHANNEL=chrome 이면 설치된 Chrome 사용 (브라우저 다운로드 불필요). CI: playwright install chromium
export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  retries: 0,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3300",
    viewport: { width: 1440, height: 900 },
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
    channel: process.env.E2E_CHANNEL || undefined,
  },
});
