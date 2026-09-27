# ADR-022 웹 메이저 업그레이드 — Next 16(webpack 빌드) · Tailwind 4(v3 모양 유지) · TypeScript 7 · React 18 유지

- 상태: 채택 (2026-09-27)
- 배경: Dependabot 메이저 PR(Next 16 · React 19 · Tailwind 4 · TypeScript 7)을 실제 스택(도커 이미지 + E2E 19개)과 측정으로
  검증했다. CI 에는 E2E 가 없어 빌드 통과만으로는 판단할 수 없다. 단계마다 typecheck · build · npm audit · 도커 이미지 · E2E 를 다시 돌렸다.

## 결정

1. **Next 16 — 운영 빌드는 webpack** (`npm run build` = `next build --webpack`, `next dev` 는 기본값 Turbopack).
   페이지 HTML 이 부르는 스크립트의 gzip 합계(KB):

   | 페이지 | Next 15 | Next 16 webpack | Next 16 Turbopack |
   |---|---|---|---|
   | / | 105.8 | 108.4 | 119.0 |
   | /rail | 98.0 | 101.6 | 112.2 |
   | /road | 101.1 | 104.0 | 114.5 |
   | /ops | 96.6 | 100.2 | 110.7 |

   Turbopack 은 페이지마다 +13~14KB, webpack 은 +2.6~3.9KB 다. 빌드 시간 차이는 이 규모에서 몇 초다.
   필수 설정은 tsconfig `jsx: react-jsx` 하나(next build 가 강제로 고쳐 쓴다).
2. **React 18 유지** — 19 는 모든 페이지의 첫 로드 JS 를 gzip 23.5KB 늘린다(react-dom 증가, / 108.4 → 131.9KB).
   Pages Router 에서 쓸 19 기능이 없다. 19 로도 빌드 · E2E 19개가 통과하는 것은 확인했다. Dependabot 은 `react*` · `@types/react*`
   메이저를 무시한다. 다시 볼 조건: Next 가 React 19 를 요구하거나 19 기능(Actions 등)이 필요할 때.
3. **Tailwind 4 — v3 와 같은 모양** (공식 도구 `@tailwindcss/upgrade` + 호환 스타일). 전후 16개 화면(8개 페이지 × 데스크톱 · 모바일)의
   모든 요소의 계산된 스타일을 비교해(같은 상태를 두 번 찍어 데이터 잡음 제거) 실제로 달라진 4가지를 되돌렸다.
   - 글자 크기의 줄 높이가 비율로 바뀌어 `text-sm` 안의 작은 글자 줄 높이가 20px → 15.7~18.6px → v3 의 rem 값
   - 버튼 기본 커서 pointer → default → pointer
   - 입력 안내문 기본색 → v3 gray-400
   - `divide-x` 가 '앞 항목의 오른쪽 선'으로 바뀌어 모바일에서 줄바꿈된 통계 줄 끝에 선이 남음 → 그 한 곳은 v3 처럼 뒤 항목의 왼쪽 선

   남은 차이는 표기만 다른 값(rgba → oklab, 그림자 층 수, `rounded-full` 의 무한대 값)과 `space-y` 여백 위치(간격은 같음)다.
   autoprefixer 는 뺐다 — v4 가 접두사를 직접 붙이고, autoprefixer 는 오히려 Safari 16.4~17 용 `-webkit-backdrop-filter` 를 지우고 있었다.
   CSS 는 gzip 6.0 → 7.7KB.
4. **TypeScript 7** — `baseUrl` 제거(없어진 옵션, import 는 모두 `@/` 별칭). next build 의 타입 검사도 TS 7 로 동작하는 것을
   일부러 넣은 타입 오류로 확인했다(`Failed to type check`). 타입 검사 2.8s → 0.4s.

## 결과

- 최소 지원 브라우저: Chrome · Edge 111+, Safari 16.4+, Firefox 128+ (Next 16 과 Tailwind 4 의 요구).
- Tailwind 4 의 `hover:` 는 호버할 수 있는 기기에서만 적용된다 — 터치 기기에서 누른 뒤 호버 색이 남던 현상이 없어진다(그대로 둠).
- 측정 방법: 페이지 HTML 의 `<script>`(nomodule 제외, 속성 대소문자 무관 — React 19 는 `noModule` 로 쓴다) gzip 합계.
  Next 16 은 빌드 출력에서 First Load JS 를 뺐다.
