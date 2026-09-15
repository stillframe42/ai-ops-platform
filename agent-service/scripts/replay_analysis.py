"""저장된 인시던트의 분석 노드만 다시 실행해 `ops.analysis.results` 로 발행한다 — 실험 2(모델 variant) 표본 확보용 (ADR-0019).

같은 인시던트를 variant 만 바꿔 다시 분석하면 짝 비교(paired)가 된다. 모니터링 요약·조치는 원본 그대로 두고
분석 노드 하나만 재실행한다 — 변인은 프롬프트 또는 모델 하나.

한계 (설계상): 분석 도구(get_app_logs·compare_with_baseline)는 "지금" 기준으로 조회한다 — 원본 시각으로 앵커되지
않으므로 근거 재조회 결과는 원본 실행과 다를 수 있다. 정착 직후·같은 날 재생에 쓰고, 리포트에 이 한계를 적는다.

사용: uv run python -m scripts.replay_analysis <incident_id> --experiment analysis-model-haiku --variant B
  환경: CONTROL_PLANE_URL(기본 http://localhost:8081) + agent-service 설정(.env — AUTH_CLIENT_SECRET·KAFKA·LLM 게이트웨이)
  --dry-run: 발행 없이 결과만 출력
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
from datetime import UTC, datetime
from pathlib import Path

import httpx

from app.agents.analysis_agent import analysis_node
from app.config import get_settings
from app.config.agent_spans import agent_node, record_experiment, workflow_span
from app.config.otel import setup_telemetry
from app.experiments.definition import load_experiments
from app.supervisor.state import ExperimentAssignment, IncidentInfo, MonitoringResult
from app.tools.oauth_client import shared_auth

logger = logging.getLogger("replay")

TOPIC_ANALYSIS_RESULTS = "ops.analysis.results"


def replay_incident_id(original: str, variant: str) -> str:
    return f"{original}-replay-{variant}"


def build_replay_state(stored: dict, assignment: ExperimentAssignment) -> dict:
    """control-plane `GET /api/incidents/{id}` 응답 → 분석 노드 입력 상태. incident_id 는 재생용으로 바꾼다 (원본 행 보존)."""
    report = stored["report"]
    monitoring = report.get("monitoring")
    return {
        "incident": IncidentInfo(
            id=replay_incident_id(stored["incident_id"], assignment.variant),
            scenario=stored["scenario"],
            alert_name=stored.get("alert_name"),
            summary=f"재생 — 원본 {stored['incident_id']}",
            # 원본 발생 시각은 보고서에 없다 — 저장 시각(created_at)이 가장 가까운 앵커
            occurred_at=stored.get("created_at") or datetime.now(UTC).isoformat(),
        ),
        "monitoring": MonitoringResult(**monitoring) if monitoring else None,
        "experiment": assignment,
        "messages": [],
    }


def build_replay_payload(stored: dict, assignment: ExperimentAssignment, update: dict) -> dict:
    """원본 보고서에서 분석만 교체한 발행 페이로드 — `replay.of` 로 원본을 가리키고 trace_ref 는 비운다."""
    report = stored["report"]
    analysis = update["analysis"].model_dump()
    analysis["prompt_version"] = update.get("analysis_prompt_version")
    return {
        "incident_id": replay_incident_id(stored["incident_id"], assignment.variant),
        "scenario": stored["scenario"],
        "alert_name": stored.get("alert_name"),
        "status": "completed",
        "monitoring": report.get("monitoring"),
        "analysis": analysis,
        "action": report.get("action"),
        "approval": report.get("approval"),
        "recovery": report.get("recovery"),
        "errors": [],
        "pending_errors": [],
        "supervisor_visits": 0,
        "trace_ref": None,
        "experiment": {"name": assignment.name, "variant": assignment.variant},
        "replay": {"of": stored["incident_id"]},
        "completed_at": datetime.now(UTC).isoformat(),
    }


def forced_assignment(experiment: str, variant: str) -> ExperimentAssignment:
    """실험 정의에서 variant 사양을 읽어 배정을 강제한다 — status 와 무관 (재생은 사람이 고른 variant)."""
    settings = get_settings()
    definition = next((d for d in load_experiments(Path(settings.experiments_file)) if d.name == experiment), None)
    if definition is None:
        raise SystemExit(f"실험 정의 없음: {experiment}")
    spec = definition.variants.get(variant)
    if spec is None:
        raise SystemExit(f"실험 {experiment} 에 variant {variant} 없음 (정의: {list(definition.variants)})")
    return ExperimentAssignment(name=experiment, variant=variant, prompt_version=spec.prompt, model_override=spec.model_override)


async def fetch_incident(control_plane_url: str, incident_id: str) -> dict:
    settings = get_settings()
    async with httpx.AsyncClient(auth=shared_auth(settings), timeout=30.0) as client:
        response = await client.get(f"{control_plane_url}/api/incidents/{incident_id}")
        response.raise_for_status()
        return response.json()


async def publish(payload: dict) -> None:
    from aiokafka import AIOKafkaProducer

    from app.events.publishing import make_publisher

    settings = get_settings()
    producer = AIOKafkaProducer(bootstrap_servers=settings.kafka_bootstrap_servers)
    await producer.start()
    try:
        await make_publisher(producer, TOPIC_ANALYSIS_RESULTS)(payload["incident_id"], payload)
    finally:
        await producer.stop()


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("incident_id")
    parser.add_argument("--experiment", required=True)
    parser.add_argument("--variant", required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    setup_telemetry(get_settings().otel_exporter_otlp_endpoint)
    control_plane_url = os.environ.get("CONTROL_PLANE_URL", "http://localhost:8081")

    assignment = forced_assignment(args.experiment, args.variant)
    stored = await fetch_incident(control_plane_url, args.incident_id)
    state = build_replay_state(stored, assignment)
    incident_id = state["incident"].id

    # 재생도 워크플로 스팬 하나 + invoke_agent analysis — 원본과 같은 트리 모양이라 Tempo 에서 variant 로 가를 수 있다
    with workflow_span(incident_id):
        record_experiment(assignment)
        update = await agent_node("analysis", analysis_node)(state)
    payload = build_replay_payload(stored, assignment, update)
    print(json.dumps({k: payload[k] for k in ("incident_id", "experiment", "replay", "analysis")}, ensure_ascii=False, indent=2))
    if args.dry_run:
        logger.info("dry-run — 발행 생략")
        return
    await publish(payload)
    logger.info("발행 완료 — %s → %s", incident_id, TOPIC_ANALYSIS_RESULTS)


if __name__ == "__main__":
    asyncio.run(main())
