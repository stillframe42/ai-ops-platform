-- 조치 승인 요청 (DAY 21) — human-in-the-loop 승인 도메인 (ADR-0005 control-plane 대행,
-- ADR-0006 Slack 승인 UX). ops.actions.pending 소비가 행을 만들고, 승인 API/Slack 버튼이
-- 상태를 전이시키고, 실행 결과까지 이 테이블이 감사 이력으로 보존한다.
-- incident_reports 와 FK 를 걸지 않는 이유: 조치안(ops.actions.pending)이 보고서 저장
-- (ops.analysis.results 소비)보다 먼저 도착할 수 있다 — 토픽 간 순서 무보장, 느슨한 결합.
-- action 상세(params·사유)가 jsonb 원문인 이유: report(jsonb) 와 같은 원칙 — 추출 버그 시 재생성 근거.
CREATE TABLE action_approvals (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id    text NOT NULL,
    action_type    text NOT NULL,             -- ActionPlan 화이트리스트 (agent-service ActionType 정합)
    action_payload jsonb NOT NULL,            -- 조치안 페이로드 원문 전체 (params·사유 포함)
    risk_level     text,                      -- P1 | P2 | P3 — 분석 에이전트 판정 (승인 요청 메시지 노출용)
    confidence     double precision,          -- 0~1
    status         text NOT NULL DEFAULT 'pending',  -- pending | approved | rejected | expired
    requested_at   timestamptz NOT NULL,      -- 발행 측 요청 시각 (페이로드 requested_at)
    decided_at     timestamptz,               -- 승인/거부/만료 시각
    decided_by     text,                      -- 승인자 Slack user ID (expired 는 'system')
    executed_at    timestamptz,               -- 조치 실행 완료 시각 (approved 에서만)
    execution_note text,                      -- 실행 결과 요약 (실패 사유 포함)
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_by     text NOT NULL DEFAULT 'system',
    updated_by     text NOT NULL DEFAULT 'system'
);

-- 활성(pending) 승인은 인시던트당 1건 — at-least-once 재발행의 멱등 짝 (upsert 관례와 같은 목적,
-- 재수신은 기존 pending 행 유지·신규 생성 차단)
CREATE UNIQUE INDEX uq_action_approvals_pending ON action_approvals (incident_id) WHERE status = 'pending';
-- 승인 대기 목록·타임아웃 스캔(30분 재알림 → 60분 expired)은 상태+요청 시각으로 조회
CREATE INDEX idx_action_approvals_status_requested ON action_approvals (status, requested_at);
