-- 인시던트 보고서 (DAY 19) — ops.analysis.results 소비 결과의 영속 저장소.
-- 요약 컬럼은 목록 조회·Slack 포맷용 추출값, 전체 보고서 원문은 report(jsonb) 가 보존한다
-- (raw 토픽 보존과 같은 원칙 — 추출 로직 버그 시 원문에서 재생성 가능).
-- 분석 요약 3종(severity/root_cause/confidence)이 nullable 인 이유: partial 보고서는
-- 분석 노드 실패로 analysis 자체가 없을 수 있다 (DAY 13 부분 보고서 구조).
CREATE TABLE incident_reports (
    incident_id  text PRIMARY KEY,
    scenario     text NOT NULL,
    alert_name   text,
    status       text NOT NULL,             -- completed | partial (발행 측 get_result 2분류)
    severity     text,                      -- P1 | P2 | P3
    root_cause   text,
    confidence   double precision,          -- 0~1
    report       jsonb NOT NULL,            -- 발행 페이로드 원문 전체
    completed_at timestamptz,               -- 발행 측 완료 시각 (페이로드 completed_at)
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- 목록 조회는 최신순 고정 (GET /api/incidents)
CREATE INDEX idx_incident_reports_created_at ON incident_reports (created_at DESC);
