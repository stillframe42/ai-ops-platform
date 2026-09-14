-- 사람 검토 결과 (DAY 48, ADR-0019 저품질 → 리뷰 → 골든셋 승격) — Judge 판정 행에 사람 라벨을 덧붙인다.
-- 별도 테이블이 아닌 이유: 검토는 평가 1건에 최대 1회이고, 승격 스크립트가 "Judge 점수 + 사람 점수" 를 한 행에서 읽는다.
-- human_scores 는 골든셋 human_scores 와 같은 형태({faithfulness, actionability, severity_accuracy}, 앵커 4단계) — 승격 시 그대로 옮긴다.
ALTER TABLE incident_evaluations
    ADD COLUMN human_scores       jsonb,        -- 사람 점수 3차원 (reviewed/promoted 에서 기입, dismissed 는 생략 가능)
    ADD COLUMN human_failure_mode text,         -- 사람이 본 실패 유형 A | B | C | D | 없음
    ADD COLUMN review_note        text,         -- 한 줄 사유 — 골든셋 note 로 승계
    ADD COLUMN reviewed_by        text,
    ADD COLUMN reviewed_at        timestamptz;
