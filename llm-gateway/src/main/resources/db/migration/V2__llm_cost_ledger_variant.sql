-- A/B 실험 variant 차원 (ADR-0019 결정 ③) — 게이트웨이가 모델을 바꾼 요청만 `<experiment>:<variant>`, 나머지는 NULL.
-- IF NOT EXISTS 인 이유: Flyway 도입 직전 기동 DDL(ALTER ADD COLUMN IF NOT EXISTS)로 이미 열이 있는 환경이 있다.
ALTER TABLE llm_cost_ledger ADD COLUMN IF NOT EXISTS variant TEXT;
