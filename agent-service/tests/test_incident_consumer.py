"""IncidentEventProcessor 계약 테스트 — aiokafka 무의존 (fake runtime/publisher 주입).

핵심 단언: 메시지 → 그래프 실행 → 결과 발행의 분기 규약.
- 건너뜀(파싱 불가·미지 scenario)은 정상 종료 — poison pill 이 커밋을 막으면 안 된다
- 인프라 실패(결과 발행 불가)만 예외 전파 — 커밋 보류 신호
- done 중복은 재실행 없이 결과만 재발행 (at-least-once 재전달 대응)
"""

import asyncio
import json

from app.events.incident_consumer import IncidentEventProcessor


def _event(**overrides) -> bytes:
    base = {
        "incident_id": "inc-error-rate-surge-20260726103000-d38f7c",
        "fingerprint": "d38f7c69cf7e2d2b",
        "scenario": "error-rate-surge",
        "alert_name": "TargetAppHighErrorRate",
        "severity": "critical",
        "summary": "5xx 에러율 10% 초과",
        "status": "firing",
        "starts_at": "2026-07-26T10:28:00Z",
        "occurred_at": "2026-07-26T10:30:00Z",
        "merge_count": 0,
    }
    base.update(overrides)
    return json.dumps(base).encode()


class FakeRuntime:
    def __init__(self, state: dict | None = None, start_error: Exception | None = None):
        self.state = state
        self.start_error = start_error
        self.started: list = []
        self.resumed: list[str] = []

    async def get_state(self, incident_id: str) -> dict | None:
        return self.state

    async def get_result(self, incident_id: str) -> dict | None:
        return {"incident_id": incident_id, "status": "completed"}

    async def start(self, incident) -> None:
        self.started.append(incident)
        if self.start_error is not None:
            raise self.start_error

    async def resume(self, incident_id: str) -> None:
        self.resumed.append(incident_id)


class RecordingPublisher:
    def __init__(self, fail: bool = False):
        self.published: list[tuple[str, dict]] = []
        self.fail = fail

    async def __call__(self, key: str, payload: dict) -> None:
        if self.fail:
            raise RuntimeError("[스텁] Kafka 발행 실패")
        self.published.append((key, payload))


def _processor(runtime: FakeRuntime, publisher: RecordingPublisher) -> IncidentEventProcessor:
    return IncidentEventProcessor(runtime, publisher)


def test_normal_event_starts_graph_and_publishes_result() -> None:
    runtime, publisher = FakeRuntime(), RecordingPublisher()

    outcome = asyncio.run(_processor(runtime, publisher).process(_event()))

    assert outcome == "processed"
    incident = runtime.started[0]
    # 이벤트 값 우선 — 프리셋의 "(수동 트리거)" 문구가 Kafka 경로에 섞이면 안 된다
    assert incident.id == "inc-error-rate-surge-20260726103000-d38f7c"
    assert incident.scenario == "error-rate-surge"
    assert incident.alert_name == "TargetAppHighErrorRate"
    assert incident.summary == "5xx 에러율 10% 초과"
    assert incident.occurred_at == "2026-07-26T10:30:00Z"
    (key, payload) = publisher.published[0]
    assert key == incident.id
    assert payload["status"] == "completed"


def test_malformed_payload_is_skipped_without_calls() -> None:
    runtime, publisher = FakeRuntime(), RecordingPublisher()

    outcome = asyncio.run(_processor(runtime, publisher).process(b"not-json{{{"))

    assert outcome == "skipped:malformed"
    assert runtime.started == [] and publisher.published == []


def test_unknown_scenario_is_skipped() -> None:
    runtime, publisher = FakeRuntime(), RecordingPublisher()

    outcome = asyncio.run(
        _processor(runtime, publisher).process(_event(scenario="disk-full"))
    )

    assert outcome == "skipped:unknown-scenario"
    assert runtime.started == []


def test_done_incident_republishes_result_without_rerun() -> None:
    runtime = FakeRuntime(state={"done": True})
    publisher = RecordingPublisher()

    outcome = asyncio.run(_processor(runtime, publisher).process(_event()))

    assert outcome == "skipped:already-done"
    assert runtime.started == [] and runtime.resumed == []
    # 결과 재발행 — 이전 사이클에서 발행 실패 후 재전달됐을 가능성에 대비 (at-least-once)
    assert len(publisher.published) == 1


def test_incomplete_checkpoint_resumes_instead_of_restart() -> None:
    """미완 체크포인트 = 처리 중 다운 후 재전달 — Durable Execution 재개 경로와 결합한다."""
    runtime = FakeRuntime(state={"done": False})
    publisher = RecordingPublisher()

    outcome = asyncio.run(_processor(runtime, publisher).process(_event()))

    assert outcome == "processed"
    assert runtime.started == []
    assert runtime.resumed == ["inc-error-rate-surge-20260726103000-d38f7c"]


def test_graph_failure_still_publishes_partial_result() -> None:
    runtime = FakeRuntime(start_error=RuntimeError("[스텁] 그래프 중단"))
    publisher = RecordingPublisher()

    outcome = asyncio.run(_processor(runtime, publisher).process(_event()))

    # 그래프 실패는 삼킨다 — 부분 결과 발행(DAY 13 부분 보고서)으로 이어가야 한다
    assert outcome == "processed"
    assert len(publisher.published) == 1


def test_publish_failure_propagates_for_commit_hold() -> None:
    runtime, publisher = FakeRuntime(), RecordingPublisher(fail=True)

    try:
        asyncio.run(_processor(runtime, publisher).process(_event()))
        raised = False
    except RuntimeError:
        raised = True

    assert raised  # 인프라 실패 — 호출자가 커밋을 보류하고 재수신해야 한다


def test_same_incident_in_flight_is_skipped() -> None:
    runtime, publisher = FakeRuntime(), RecordingPublisher()
    processor = _processor(runtime, publisher)
    processor._active.add("inc-error-rate-surge-20260726103000-d38f7c")

    outcome = asyncio.run(processor.process(_event()))

    assert outcome == "skipped:running"
    assert runtime.started == []
