# syntax=docker/dockerfile:1.7
# collector + analytics (Python 3.11). 빌드 컨텍스트는 저장소 루트 (seed/ 를 함께 넣기 위해)
FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 TZ=Asia/Seoul
WORKDIR /app/collector
COPY collector/pyproject.toml ./
RUN --mount=type=cache,target=/root/.cache/pip pip install --upgrade pip \
 && pip install httpx==0.28.1 "psycopg[binary,pool]==3.2.10" redis==6.4.0 APScheduler==3.11.0 pandas==2.3.2 \
      numpy==2.3.3 PyYAML==6.0.2 pydantic-settings==2.10.1 pytest==8.4.2 pytest-asyncio==1.2.0
COPY collector/roadrail ./roadrail
COPY collector/tests ./tests
RUN pip install --no-deps -e .
COPY seed /app/seed
COPY fixtures /app/fixtures
COPY db/migrations /app/db/migrations
RUN useradd -r app && chown -R app /app
USER app
CMD ["roadrail", "serve"]
