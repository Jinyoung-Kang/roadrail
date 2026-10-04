import { expect, test } from "@playwright/test";

// 스택이 떠 있는 상태(make up)에서 실행: make e2e
test("판단 화면: 출발지 → 도착지 · 판단 요약 · 스펙 · 근거", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("서울역");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("대전역");
  await expect(page.getByText(/빠를 것으로 보입니다|비슷합니다|비교할 수 없습니다|비교합니다/).first()).toBeVisible();
  await expect(page.getByText("자동차", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("판단 근거 · R-DEC-01")).toBeVisible();
  await expect(page.getByText("데이터 시각", { exact: true })).toBeVisible();
});

test("판단 화면: ⇄ 로 출발지·도착지를 바꾼다", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("서울역");
  await page.getByRole("button", { name: "출발지와 도착지 바꾸기" }).click();
  await expect(page).toHaveURL(/from=%EB%8C%80%EC%A0%84/);  // 대전
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(/대전역\s*→\s*서울역/);
});

test("판단 화면: 전국 어디든 검색해서 고른다", async ({ page }) => {
  await page.goto("/");
  const to = page.getByRole("combobox", { name: "도착지" });
  await to.fill("전주");
  await expect(page.getByRole("option").first()).toBeVisible({ timeout: 10_000 });
  await page.getByRole("option", { name: /전주시/ }).first().click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText("전주시");
  await expect(page.getByText(/빠를 것으로 보입니다|비슷합니다|비교할 수 없습니다|비교합니다/).first()).toBeVisible({ timeout: 15_000 });
});

test("판단 화면: 검색 칸은 처음엔 비어 있고 입력한 단어로만 찾는다", async ({ page }) => {
  await page.goto("/");
  const from = page.getByRole("combobox", { name: "출발지" });
  await from.click();
  await expect(page.getByRole("listbox")).toHaveCount(0);  // 추천·즐겨찾기 목록을 띄우지 않는다
  await from.fill("수원");
  await expect(page.getByRole("option").first()).toBeVisible({ timeout: 10_000 });
  for (const name of await page.getByRole("listbox").getByRole("option").allInnerTexts()) expect(name).toContain("수원");
});

test("메인: 서비스 소개 — 한 문장 정의 · 3단계 · 다른 메뉴", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: /소개/ }).click();
  const about = page.locator("#about");
  await expect(about.getByRole("heading", { name: /차로 갈까, 기차로 갈까/ })).toBeInViewport();
  await expect(about.getByText("같은 출발 시각으로 비교")).toBeVisible();
  await expect(about.getByRole("link", { name: /철도 분석/ })).toHaveAttribute("href", "/rail");
});

test("판단 근거: 돌발 경고가 있으면 무슨 안내인지 목록으로 보인다", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("판단 근거 · R-DEC-01")).toBeVisible({ timeout: 15_000 });
  const warn = page.getByText(/경로 주변 돌발 안내 \d+건/);
  if (await warn.count() === 0) return;  // 지금 안내가 없으면 여기까지
  const list = page.getByRole("list", { name: "돌발 안내 목록" });
  await expect(list.getByRole("listitem").first()).toBeVisible();
  // 시각의 뜻은 출처마다 다르다 — 도로공사 문자는 발송, 경찰청 UTIC 는 돌발 시작(· 종료 예정)
  await expect(list.getByText(/발송|시작/).first()).toBeVisible();
});

test("판단 근거: 경찰청 UTIC 돌발은 경로 위의 것만 · 출처와 시작 시각을 단다", async ({ page }) => {
  // 화면이 실제로 받은 응답으로 확인한다 (계산 중이면 화면이 다시 부른다 — 마지막 응답까지 기다림)
  const done = page.waitForResponse(async (r) => r.url().includes("/api/v1/trip?") && !(await r.json()).pending,
    { timeout: 30_000 });
  await page.goto("/");
  const d = await (await done).json();
  const utic: { routeKm: number | null }[] = (d.incidents ?? []).filter((i: { source?: string }) => i.source === "UTIC");
  test.skip(utic.length === 0, "지금 경로 주변에 UTIC 돌발이 없거나 UTIC 키가 없는 환경");
  // 일반 도로 돌발은 경로에서 0.5km 안만 (2km 로는 강변북로 · 성북로처럼 나란한 다른 길이 섞였다 — 검증 기록 75)
  for (const i of utic) expect(i.routeKm).not.toBeNull();
  for (const i of utic) expect(i.routeKm!).toBeLessThanOrEqual(0.5);
  // 경고의 건수는 목록 상한과 무관한 전체 건수 (예전엔 8건 상한에 걸려 건수까지 줄었다)
  await expect(page.getByText(`경로 주변 돌발 안내 ${d.incidentTotal}건`)).toBeVisible();
  const more = page.getByRole("button", { name: /나머지 \d+건 더 보기/ });
  if (await more.count()) await more.click();   // 처음 5건만 펼쳐 둔다
  const list = page.getByRole("list", { name: "돌발 안내 목록" });
  await expect(list.getByRole("listitem")).toHaveCount(d.incidents.length);
  await expect(list.getByText("경찰청 UTIC", { exact: true })).toHaveCount(utic.length);
  await expect(list.getByText(/시작/).first()).toBeVisible();
});

test("판단 화면: 자동차·기차 카드 — 도착 예정 · 시간 구성 · 선로 지도 출처", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("도착 예정").first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("img", { name: /시간 구성: .*탑승/ })).toBeVisible();
  await expect(page.getByText("선로 © OpenStreetMap contributors (ODbL)", { exact: true })).toBeAttached();
});

test("도로 분석: 전국 어디든 — 출발 시각별 · 도로 구성", async ({ page }) => {
  await page.goto("/road?from=%EA%B0%95%EB%82%A8%EC%97%AD~37.49790~127.02760~&to=%EC%A0%84%EC%A3%BC%EC%8B%9C~35.82420~127.14800~");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(/강남역\s*→\s*전주시/);
  await expect(page.getByRole("heading", { name: "언제 출발하면 빠를까" })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole("heading", { name: "어떤 도로로 가나요" })).toBeVisible();
  await expect(page.getByText("고속도로 비율 (거리)")).toBeVisible();
});

test("지도: 기본은 잠김 — 스크롤해도 배율이 바뀌지 않고 버튼으로 조작", async ({ page }) => {
  await page.goto("/");
  const btn = page.getByRole("button", { name: "지도 조작하기 (이동 · 확대)" });
  await expect(btn).toBeVisible({ timeout: 15_000 });
  await btn.click();
  await expect(page.getByRole("button", { name: "지도 조작 끄기" })).toBeVisible();
});

test("선택 목록: 맨 아래 항목까지 잘리지 않는다", async ({ page }) => {
  await page.goto("/rail?dep=3900023&arr=3900073");
  await page.getByRole("combobox", { name: "도착역" }).click();
  const list = page.getByRole("listbox");
  await expect(list.getByRole("option").first()).toBeVisible();
  await list.evaluate((el) => (el.scrollTop = el.scrollHeight));
  const last = list.getByRole("option").last();
  await expect(last).toBeInViewport();
  const box = await last.boundingBox();
  const vh = page.viewportSize()!.height;
  expect(box!.y + box!.height).toBeLessThanOrEqual(vh);
});

test("고속도로 실측 분석(길): 추이 차트와 히트맵", async ({ page }) => {
  await page.goto("/road/SEL-DJN?dir=DN");
  await expect(page.getByRole("heading", { name: "통행시간과 기준선" })).toBeVisible();
  await expect(page.locator(".recharts-line").first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "요일 × 시간 히트맵" })).toBeVisible();
});

test("철도 분석: 임의 역 쌍 — 역 검색으로 바꾼다", async ({ page }) => {
  await page.goto("/rail?dep=3900023&arr=3900073");
  await expect(page.getByText(/정시율 \(도착 ≤5분\)/)).toBeVisible({ timeout: 15_000 });
  await page.getByRole("combobox", { name: "도착역" }).fill("부산");
  await page.getByRole("option", { name: /^부산역/ }).first().click();
  await expect(page).toHaveURL(/arr=3900114/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(/서울역\s*→\s*부산역/);
  await expect(page.getByRole("heading", { name: "정시율 랭킹" })).toBeVisible();
});

test("철도 분석: 역 선택 목록은 가나다순 · 차종은 TAGO 시간표 값만 · OO발 OO행", async ({ page }) => {
  await page.goto("/rail?dep=3900023&arr=3900114");
  await page.getByRole("combobox", { name: "출발역" }).click();
  await expect(page.getByRole("listbox").getByRole("option").nth(100)).toBeAttached({ timeout: 15_000 });
  const names = (await page.getByRole("listbox").getByRole("option").allInnerTexts()).map((t) => t.split("\n")[0].replace(/역$/, ""));  // 광주 < 광주송정
  expect(names.length).toBeGreaterThan(100);
  expect(names).toEqual([...names].sort());
  await page.keyboard.press("Escape");
  await expect(page.getByText(/서울발 부산행/).first()).toBeVisible({ timeout: 10_000 });
  // 차종은 TAGO 시간표에서 온 배지로만 나온다 (번호로 추정한 값 없음)
  const kinds = page.getByText(/^(KTX|SRT|ITX|무궁화호|새마을호|누리로|통근열차)/);
  const n = await kinds.count();
  expect(await page.getByTitle("TAGO 열차 시간표의 그날 배정 차종").count()).toBe(n);
});

test("수집 상태: 작업 표 · 예산 · 오류 상세와 복사", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto("/ops");
  await expect(page.getByText("road_travel_time").first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "공급자별 오늘 호출 예산" })).toBeVisible();
  const heading = page.getByRole("heading", { name: /최근 24시간 오류/ });
  await expect(heading).toBeVisible();
  if (/없음/.test(await heading.innerText())) return;  // 오류가 없으면 여기까지
  await page.getByRole("button", { name: /전체 \d+건 복사/ }).click();
  await expect(page.getByRole("button", { name: /복사됨/ })).toBeVisible();
  const text = await page.evaluate(() => navigator.clipboard.readText());
  expect(text).toMatch(/작업: \w+ · 트리거/);
});

test("모바일: 메뉴 드로어", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "메뉴" }).click();
  await expect(page.getByRole("dialog").getByRole("link", { name: "철도 분석" })).toBeVisible();
});

test("보안 헤더(CSP 등) · 콘솔 오류 없음 · 카카오 지도는 CSP 안에서 동작", async ({ page }) => {
  const errors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text().slice(0, 200)); });
  page.on("pageerror", (e) => errors.push(e.message));
  const res = await page.goto("/", { waitUntil: "networkidle" });
  const h = res!.headers();
  expect(h["content-security-policy"]).toContain("frame-ancestors 'none'");
  expect(h["x-content-type-options"]).toBe("nosniff");
  expect(h["x-frame-options"]).toBe("DENY");
  await expect(page.getByRole("button", { name: "지도 조작하기 (이동 · 확대)" })).toBeVisible({ timeout: 15_000 });
  expect(await page.evaluate(() => !!(window as any).kakao?.maps?.Map)).toBe(true);
  expect(errors).toEqual([]);
});

test("요청 한도: 클라이언트가 X-Forwarded-For 를 위조해도 같은 한도에서 센다", async ({ request }) => {
  const r1 = await request.get("/api/v1/places/search?q=%EB%8C%80%EC%A0%84", { headers: { "X-Forwarded-For": "203.0.113.1" } });
  const r2 = await request.get("/api/v1/places/search?q=%EB%8C%80%EC%A0%84", { headers: { "X-Forwarded-For": "203.0.113.2" } });
  expect(r1.ok() && r2.ok()).toBe(true);
  const left = (r: typeof r1) => Number(r.headers()["x-ratelimit-remaining"]);
  expect(left(r2)).toBe(left(r1) - 1);  // 주소를 바꿔도 새 한도가 생기지 않음
});

test("보안: URL 의 장소 이름은 지도 라벨에 글자로만 들어간다 (HTML 주입 차단)", async ({ page }) => {
  // 이름은 URL(공유 링크) · API 에코 · 외부 데이터에서 온다 — 지도 표식은 HTML 문자열이 아니라 텍스트여야 한다
  const evil = '<b id="rr-inj">주입</b>';
  await page.goto(`/?from=${encodeURIComponent(`${evil}~37.55470~126.97060~`)}&to=${encodeURIComponent("대전역~36.33250~127.43430~3900073")}`);
  const map = page.getByLabel("경로 지도", { exact: true });
  await expect(map.getByText(evil, { exact: true })).toBeVisible({ timeout: 15_000 });  // 꺾쇠까지 글자 그대로
  expect(await page.locator("#rr-inj").count()).toBe(0);
});

test("보안: 프록시는 /api/v1 아래의 정상 경로만 넘긴다", async ({ request }) => {
  // 인코딩한 '../' 로 API 서버의 다른 경로를 노리는 요청 → 프록시가 JSON 400 으로 거절 (Tomcat HTML 이 아님)
  const r = await request.get("/api/v1/..%2f..%2factuator/health");
  expect(r.status()).toBe(400);
  expect((await r.json()).code).toBe("VALIDATION_ERROR");
  expect((await request.get("/api/v1/corridors")).ok()).toBe(true);
});

test("보안: 프록시는 허용한 메서드만 넘기고, API 문서 경로도 다른 사이트에 끼워 넣을 수 없다", async ({ request }) => {
  // WEB-09: TRACE 등은 API 까지 가지 않고 405 + Allow (예전: 502 '연결할 수 없습니다')
  const r = await request.fetch("/api/v1/corridors", { method: "TRACE" });
  expect(r.status()).toBe(405);
  expect(r.headers()["allow"]).toContain("GET");
  expect((await r.json()).code).toBe("METHOD_NOT_ALLOWED");
  // WEB-10: Swagger UI 경로는 보안 헤더가 하나도 없어 다른 사이트가 프레임으로 넣을 수 있었다(클릭재킹)
  const docs = await request.get("/swagger-ui/index.html");
  expect(docs.headers()["x-frame-options"]).toBe("DENY");
  expect(docs.headers()["x-content-type-options"]).toBe("nosniff");
});

test("자동차 경로: 구간별 소통이 경로 전체를 빈틈없이 덮고, 지도 범례는 느린 구간과 일치한다", async ({ page, request }) => {
  const q = "fromLat=37.5547&fromLon=126.9707&fromName=%EC%84%9C%EC%9A%B8%EC%97%AD&toLat=36.3326&toLon=127.4342&toName=%EB%8C%80%EC%A0%84%EC%97%AD&departIn=0";
  const d = await (await request.get(`/api/v1/road/route?${q}`)).json();
  const rec = d.recommended;
  test.skip(!rec?.path?.length, "카카오 키가 없는 환경");
  const runs: { from: number; to: number; traffic: string; distanceM: number }[] = rec.traffic;
  expect(rec.path.length).toBeLessThanOrEqual(400);                       // 지도에 보내는 좌표 상한
  expect(runs[0].from).toBe(0);
  expect(runs[runs.length - 1].to).toBe(rec.path.length - 1);
  runs.forEach((r, i) => { if (i > 0) expect(r.from).toBe(runs[i - 1].to); });   // 이웃 구간은 경계 점을 함께 쓴다
  for (const r of runs) expect(["원활", "서행", "지체", "정체", "사고", "정보 없음"]).toContain(r.traffic);
  const total = runs.reduce((s, r) => s + r.distanceM, 0);
  expect(Math.abs(total - rec.distanceM) / rec.distanceM).toBeLessThan(0.02);   // 구간 길이 합 = 경로 거리
  // 느린 구간 목록은 소통이 같은 도로만 합친다 — 한 도로의 느린 구간이 그 소통의 전체 길이를 넘지 않는다
  const slowM = (t: string) => runs.filter((r) => r.traffic === t).reduce((s, r) => s + r.distanceM, 0);
  for (const s of rec.slow) expect(s.distanceM).toBeLessThanOrEqual(slowM(s.traffic) + 1);

  await page.goto(`/road?from=${encodeURIComponent("서울역~37.5547~126.9707~")}&to=${encodeURIComponent("대전역~36.3326~127.4342~")}`);
  await expect(page.getByRole("heading", { name: "어떤 도로로 가나요" })).toBeVisible({ timeout: 20_000 });
  const legend = page.getByText("추천 경로 · 카카오 예측");
  const anySlow = runs.some((r) => ["지체", "정체", "사고"].includes(r.traffic));
  if (anySlow) await expect(legend).toBeVisible(); else await expect(legend).toHaveCount(0);
});
