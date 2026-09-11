-- 보고서 품질 평가 (DAY 47, ADR-0019) — ops.evaluation.results 소비 결과의 영속 저장소.
-- 한 행 = Judge 판정 1건. 같은 보고서를 다른 Judge 프롬프트·모델로 다시 채점하면 행이 늘어난다 —
-- (incident_id, prompt_version, judge_model) 이 자연 키이자 at-least-once 재전달의 멱등 짝 (upsert).
-- incident_reports 와 FK 를 걸지 않는 이유: action_approvals 와 같다 — 평가는 별도 컨슈머 그룹이 발행하므로
-- 보고서 저장(ops.analysis.results 소비)보다 먼저 도착할 수 있다.
-- 차원 점수 3종을 컬럼으로 두는 이유: 리뷰 큐 정렬·SLO 조회가 SQL 로 가능해야 한다. 이유 문장·전체 원문은 evaluation(jsonb).
CREATE TABLE incident_evaluations (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id             text NOT NULL,
    prompt_version          text NOT NULL,             -- Judge 프롬프트 버전
    judge_model             text NOT NULL,             -- 게이트웨이가 실제로 쓴 모델 (응답 model)
    analysis_prompt_version text,                      -- 평가 대상 분석 프롬프트 버전 (구버전 보고서는 null) — 실험 축
    faithfulness            double precision NOT NULL, -- 앵커 1.0 | 0.7 | 0.4 | 0.0
    actionability           double precision NOT NULL,
    severity_accuracy       double precision NOT NULL,
    failure_mode            text NOT NULL,             -- A | B | C | D | 없음
    low_quality             boolean NOT NULL,          -- 어느 차원이든 < 0.7 (발행 측 판정 그대로 보존)
    evidence_available      boolean NOT NULL,          -- 시간창 재조회 근거 유무
    evaluation              jsonb NOT NULL,            -- 발행 페이로드 원문 전체 (차원별 reason 포함)
    -- 사람 검토 흐름의 상태 — low_quality 면 pending_review 로 시작, 아니면 not_required.
    -- reviewed / promoted(골든셋 승격) / dismissed(Judge 오판) 전이는 리뷰 API 몫
    review_status           text NOT NULL,
    evaluated_at            timestamptz NOT NULL,      -- 발행 측 평가 시각 (페이로드 evaluated_at)
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              text NOT NULL DEFAULT 'system',
    updated_by              text NOT NULL DEFAULT 'system'
);

CREATE UNIQUE INDEX uq_incident_evaluations_judge ON incident_evaluations (incident_id, prompt_version, judge_model);
-- 리뷰 큐 조회 (status 별 최신순) — pending_review 만 자주 읽지만 부분 인덱스는 status 전이(reviewed 등)까지 덮지 못해 전체 컬럼으로
CREATE INDEX idx_incident_evaluations_review ON incident_evaluations (review_status, evaluated_at DESC);
