# evaluation-service — 보고서 품질 평가 컨슈머 (ADR-0019). agent-service Dockerfile 과 같은 골격, libpq 불필요
FROM ghcr.io/astral-sh/uv:python3.13-bookworm-slim

WORKDIR /app

# 의존성 레이어 분리 — 코드 변경 시 재설치를 피한다 (uv.lock 고정 버전 그대로)
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

COPY evaluation ./evaluation

# stdout 버퍼링이 로그를 지연·유실시키지 않게 — 샘플링 결정 로그가 실측 근거
ENV PYTHONUNBUFFERED=1
ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8000
CMD ["uvicorn", "evaluation.main:app", "--host", "0.0.0.0", "--port", "8000"]
