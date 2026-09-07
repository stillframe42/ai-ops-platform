"""DecisionEventProcessor 계약 테스트 — ops.actions.decisions → 그래프 재개 (ADR-0005).

aiokafka 무의존 (fake runtime/publisher 주입) — incident_consumer 테스트와 같은 관례.
핵심 단언: 결정 1건 → 검증 → resume → 최종 보고서 발행의 분기 규약.
- 건너뜀(파싱 불가·미지 인시던트·불량 status)은 정상 종료 — poison 결정이
  인시던트를 잘못 종결시키거나 커밋을 막으면 안 된다
- 인프라 실패(보고서 발행 불가)만 예외 전파 — 커밋 보류 신호
"""

import asyncio
import json

from app.events.decisions_consumer import DecisionEventProcessor


def _decision(**overrides) -> bytes:
    base = {
        "incident_id": "inc-memory-pressure-20260801100000-ab12cd",
        "status": "approved",
        "decided_by": "U0123ABC",
        "decided_at": "2026-08-01T10:05:00Z",
        "note": "",
    }
    base.update(overrides)
    return json.dumps(base).encode()


class FakeRuntime:
    def __init__(self, state: dict | None = None, resume_error: Exception | None = None):
        self.state = state
        self.resume_error = resume_error
        self.resumed: list[tuple[str, dict]] = []

    async def get_state(self, incident_id: str) -> dict | None:
        return self.state

    async def get_result(self, incident_id: str) -> dict | None:
        return {"incident_id": incident_id, "status": "completed"}

    async def resume_with_decision(self, incident_id: str, decision: dict, parent_context=None) -> None:
        self.resumed.append((incident_id, decision))
        if self.resume_error is not None:
            raise self.resume_error


class RecordingPublisher:
    def __init__(self, fail: bool = False):
        self.published: list[tuple[str, dict]] = []
        self.fail = fail

    async def __call__(self, key: str, payload: dict) -> None:
        if self.fail:
            raise RuntimeError("[스텁] Kafka 발행 실패")
        self.published.append((key, payload))


def _waiting_state() -> dict:
    return {"done": False, "awaiting_approval": True}


def test_approved_decision_resumes_and_publishes_result() -> None:
    runtime, publisher = FakeRuntime(state=_waiting_state()), RecordingPublisher()

    outcome = asyncio.run(DecisionEventProcessor(runtime, publisher).process(_decision()))

    assert outcome == "processed"
    (incident_id, decision) = runtime.resumed[0]
    assert incident_id == "inc-memory-pressure-20260801100000-ab12cd"
    assert decision["status"] == "approved"
    assert decision["decided_by"] == "U0123ABC"
    (key, payload) = publisher.published[0]
    assert key == incident_id
    assert payload["status"] == "completed"


def test_malformed_payload_is_skipped_without_calls() -> None:
    runtime, publisher = FakeRuntime(state=_waiting_state()), RecordingPublisher()

    outcome = asyncio.run(DecisionEventProcessor(runtime, publisher).process(b"not-json{{{"))

    assert outcome == "skipped:malformed"
    assert runtime.resumed == [] and publisher.published == []


def test_missing_incident_id_is_skipped() -> None:
    runtime, publisher = FakeRuntime(state=_waiting_state()), RecordingPublisher()

    outcome = asyncio.run(
        DecisionEventProcessor(runtime, publisher).process(_decision(incident_id=""))
    )

    assert outcome == "skipped:no-incident-id"
    assert runtime.resumed == []


def test_invalid_status_is_skipped() -> None:
    """불량 status 로 resume 하면 안전 측 거부로 종결돼 버린다 — 재개 전에 걸러야 한다."""
    runtime, publisher = FakeRuntime(state=_waiting_state()), RecordingPublisher()

    outcome = asyncio.run(
        DecisionEventProcessor(runtime, publisher).process(_decision(status="maybe"))
    )

    assert outcome == "skipped:invalid-status"
    assert runtime.resumed == []


def test_unknown_incident_is_skipped() -> None:
    runtime, publisher = FakeRuntime(state=None), RecordingPublisher()

    outcome = asyncio.run(DecisionEventProcessor(runtime, publisher).process(_decision()))

    assert outcome == "skipped:unknown-incident"
    assert runtime.resumed == []


def test_done_incident_republishes_result_without_resume() -> None:
    """재개 후 발행 실패 → 재전달 시나리오 — 보고서 발행이 빠지면 영영 발행되지 않는다."""
    runtime, publisher = FakeRuntime(state={"done": True}), RecordingPublisher()

    outcome = asyncio.run(DecisionEventProcessor(runtime, publisher).process(_decision()))

    assert outcome == "skipped:already-done"
    assert runtime.resumed == []
    assert len(publisher.published) == 1


def test_not_waiting_incident_is_skipped() -> None:
    """승인 대기가 아닌 미완 인시던트 — 실행 중 그래프에 결정을 밀어 넣지 않는다."""
    runtime = FakeRuntime(state={"done": False, "awaiting_approval": False})
    publisher = RecordingPublisher()

    outcome = asyncio.run(DecisionEventProcessor(runtime, publisher).process(_decision()))

    assert outcome == "skipped:not-waiting"
    assert runtime.resumed == []


def test_resume_failure_still_publishes_partial_result() -> None:
    runtime = FakeRuntime(state=_waiting_state(), resume_error=RuntimeError("[스텁] 재개 실패"))
    publisher = RecordingPublisher()

    outcome = asyncio.run(DecisionEventProcessor(runtime, publisher).process(_decision()))

    # 재개 실패는 삼킨다 — 부분 보고서 발행(DAY 13 관례)으로 이어가야 한다
    assert outcome == "processed"
    assert len(publisher.published) == 1


def test_publish_failure_propagates_for_commit_hold() -> None:
    runtime = FakeRuntime(state=_waiting_state())
    publisher = RecordingPublisher(fail=True)

    try:
        asyncio.run(DecisionEventProcessor(runtime, publisher).process(_decision()))
        raised = False
    except RuntimeError:
        raised = True

    assert raised  # 인프라 실패 — 호출자가 커밋을 보류하고 재수신해야 한다
