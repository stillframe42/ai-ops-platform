# agent-service — LangGraph 멀티 에이전트 (Durable Execution 은 postgres 컨테이너 전제)
FROM ghcr.io/astral-sh/uv:python3.13-bookworm-slim

WORKDIR /app

# psycopg(pure Python 구현)는 런타임에 libpq 를 동적 로드한다 — slim 이미지엔 없어 설치 필수
RUN apt-get update && apt-get install -y --no-install-recommends libpq5 \
    && rm -rf /var/lib/apt/lists/*

# 의존성 레이어 분리 — 코드 변경 시 재설치를 피한다 (uv.lock 고정 버전 그대로)
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

COPY app ./app

# stdout 버퍼링이 로그를 지연·유실시키지 않게 — 로그 시각이 측정 근거인 프로젝트 특성
ENV PYTHONUNBUFFERED=1
ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
