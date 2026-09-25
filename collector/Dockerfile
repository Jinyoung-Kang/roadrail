# syntax=docker/dockerfile:1.7
# collector + analytics (Python 3.11). 빌드 컨텍스트는 저장소 루트 (seed/ · fixtures/ · db/migrations 를 함께 넣기 위해)
#
#   runtime  운영 이미지 — 의존성은 pyproject.toml 한 곳에서만 (버전 고정), 테스트 도구 · 테스트 코드 없음
#   test     runtime + pytest · ruff · pip-audit + tests · fixtures · migrations  (make test-collector / CI)

FROM python:3.14-slim AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1 TZ=Asia/Seoul
WORKDIR /app/collector
COPY collector/pyproject.toml ./
# 의존성 층을 코드와 분리해 캐시 (코드만 바뀌면 다시 설치하지 않음)
# 베이스 이미지의 pip 24.0 · setuptools 79.0.1 은 알려진 취약점이 있어(pip-audit) 고친 버전으로 고정
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install pip==26.2.1 setuptools==84.0.0 \
 && python -c "import tomllib; print('\n'.join(tomllib.load(open('pyproject.toml','rb'))['project']['dependencies']))" > /tmp/req.txt \
 && pip install -r /tmp/req.txt
COPY collector/roadrail ./roadrail
RUN pip install --no-deps -e . && useradd -r -u 10001 app
COPY seed /app/seed
USER app
CMD ["roadrail", "serve"]

FROM runtime AS test
USER root
RUN --mount=type=cache,target=/root/.cache/pip \
    python -c "import tomllib; print('\n'.join(tomllib.load(open('pyproject.toml','rb'))['project']['optional-dependencies']['dev']))" > /tmp/dev.txt \
 && pip install -r /tmp/dev.txt
COPY collector/tests ./tests
COPY fixtures /app/fixtures
COPY db/migrations /app/db/migrations
USER app
CMD ["pytest", "-q"]
