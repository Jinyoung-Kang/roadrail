# Next.js 16 (Pages Router, React 18) — standalone 출력
# 운영 빌드는 webpack(npm run build = next build --webpack): 16 기본값 Turbopack 은 페이지마다 첫 로드 JS 가 gzip 약 14KB 많다(실측)
FROM node:24-bookworm-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM node:24-bookworm-slim AS build
WORKDIR /app
# rewrites 는 빌드 시점에 고정 → 컨테이너 네트워크 주소를 인자로
ARG API_INTERNAL_URL=http://api:8300
# 브라우저에 노출되는 유일한 키 (카카오 JS, 도메인 제한 키)
ARG NEXT_PUBLIC_KAKAO_JS_KEY=
# HTTPS 로 공개할 때 1 — CSP 에서 http 지도 출처를 빼고 upgrade-insecure-requests · HSTS (next.config.mjs)
ARG PUBLIC_HTTPS=
# API 문서 링크 — API 의 SWAGGER_ENABLED 와 같은 값(true 일 때만 메뉴 · 꼬리말에 /docs 링크)
ARG NEXT_PUBLIC_API_DOCS=
ENV API_INTERNAL_URL=$API_INTERNAL_URL NEXT_PUBLIC_KAKAO_JS_KEY=$NEXT_PUBLIC_KAKAO_JS_KEY PUBLIC_HTTPS=$PUBLIC_HTTPS \
    NEXT_PUBLIC_API_DOCS=$NEXT_PUBLIC_API_DOCS NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN rm -f .env.local && npm run build

FROM node:24-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3300 HOSTNAME=0.0.0.0 TZ=Asia/Seoul
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
USER node
EXPOSE 3300
CMD ["node", "server.js"]
