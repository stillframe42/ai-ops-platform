-- 감사 주체 컬럼 (DAY 19) — Spring Data Auditing (@CreatedBy/@LastModifiedBy) 도입.
-- 현재 쓰기 주체는 결과 컨슈머뿐이라 'system' 고정 — 4주차 human-in-the-loop 승인에서
-- 실제 주체(승인자)로 대체된다 (AuditorAware 교체 지점은 JpaAuditingConfig).
-- DEFAULT 는 기존 행 backfill + 비-JPA 수동 조작의 안전망 (created_at DEFAULT now() 와 같은 역할).
ALTER TABLE incident_reports
    ADD COLUMN created_by text NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by text NOT NULL DEFAULT 'system';
