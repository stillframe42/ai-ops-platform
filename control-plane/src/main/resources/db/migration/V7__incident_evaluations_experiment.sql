-- A/B 실험 축 (DAY 49, ADR-0019 결정 ③) — 평가 페이로드의 experiment_name·experiment_variant 를 컬럼으로 둔다.
-- analysis_prompt_version 과 별도인 이유: 실험은 프롬프트 버전 외 축(모델·온도 등)으로도 나뉘고, 같은 버전이 여러 실험에
-- 재사용된다 — variant 별 집계(n·차원 평균·저품질률)에는 실험 이름이 그룹 키여야 한다. 실험 밖 평가는 둘 다 null.
ALTER TABLE incident_evaluations
    ADD COLUMN experiment_name    text,
    ADD COLUMN experiment_variant text;

-- 실험 요약 API 는 실험 이름으로 전건을 읽어 variant 별로 나눈다 — 이름 단독 조회도 선두 컬럼으로 덮인다
CREATE INDEX idx_incident_evaluations_experiment ON incident_evaluations (experiment_name, experiment_variant);
