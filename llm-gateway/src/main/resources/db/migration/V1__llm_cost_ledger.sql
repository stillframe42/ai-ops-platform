-- 비용 원장 (llmgateway DB — 의미 캐시 vector_store 와 공용). 원장 도입(8/19) 이후 기동 시 멱등 DDL 로 만들던 것을
-- 첫 스키마 변경(variant 열) 시점에 Flyway 로 옮겼다 (2026-09-15). IF NOT EXISTS 인 이유: 이미 원장이 있는 환경(compose·kind)
-- 을 baseline 0 에서 다시 V1 부터 적용하기 때문 — 새 DB 와 기존 DB 가 같은 경로를 지난다.
-- vector_store 는 Spring AI pgvector 가 소유한다 (initialize-schema) — control-plane 과 같은 경계.
CREATE TABLE IF NOT EXISTS llm_cost_ledger (
    id                BIGSERIAL PRIMARY KEY,
    occurred_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    "day"             DATE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')::date,  -- 따옴표: day 는 H2 예약어 (PostgreSQL 은 소문자 동일)
    service           TEXT NOT NULL,
    task              TEXT,
    provider          TEXT NOT NULL,
    model             TEXT NOT NULL,
    cache_status      TEXT NOT NULL,
    prompt_tokens     INT NOT NULL,
    completion_tokens INT NOT NULL,
    cost_usd          NUMERIC(12, 6) NOT NULL,
    saved_usd         NUMERIC(12, 6) NOT NULL
);

-- 집계 차원 질의(서비스별/일별)용 — 태스크·모델은 스캔 규모상 인덱스 불요 (데모 규모)
CREATE INDEX IF NOT EXISTS idx_llm_cost_ledger_day_service ON llm_cost_ledger ("day", service);
