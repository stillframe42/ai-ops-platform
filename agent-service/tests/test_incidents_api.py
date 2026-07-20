"""인시던트 API 의 HTTP 매핑 테스트 — 그래프 실행은 스텁 런타임으로 대체한다.

체크포인터·재개 계약은 test_runtime.py 몫 — 여기서는 상태 코드/페이로드/404·409 경계만 본다.
TestClient 를 컨텍스트 매니저 없이 쓰므로 lifespan(실 DB 연결)은 실행되지 않는다.
"""

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.supervisor.state import IncidentInfo


class _StubRuntime:
    def __init__(self) -> None:
        self.started: list[IncidentInfo] = []
        self.resumed: list[str] = []
        self.states: dict[str, dict] = {}
        self.histories: dict[str, list[dict]] = {}

    def start_background(self, incident: IncidentInfo) -> None:
        self.started.append(incident)

    def resume_background(self, incident_id: str) -> None:
        self.resumed.append(incident_id)

    async def get_state(self, incident_id: str) -> dict | None:
        return self.states.get(incident_id)

    async def get_history(self, incident_id: str) -> list[dict]:
        return self.histories.get(incident_id, [])


@pytest.fixture
def api() -> tuple[TestClient, _StubRuntime]:
    stub = _StubRuntime()
    app.state.runtime = stub
    yield TestClient(app), stub
    del app.state.runtime


def test_trigger_returns_202_with_preset_incident(api) -> None:
    client, stub = api
    response = client.post("/incidents/trigger", json={"scenario": "memory-pressure"})

    assert response.status_code == 202
    body = response.json()
    assert body["incident_id"].startswith("inc-memory-pressure-")
    assert body["alert_name"] == "TargetAppHeapUsageHigh"
    # 백그라운드 실행에 넘긴 인시던트가 응답과 동일하다 (thread_id = incident_id)
    assert [i.id for i in stub.started] == [body["incident_id"]]


def test_trigger_rejects_unknown_scenario(api) -> None:
    client, _ = api
    assert client.post("/incidents/trigger", json={"scenario": "unknown"}).status_code == 422


def test_resume_requires_existing_checkpoint(api) -> None:
    client, stub = api
    assert client.post("/incidents/inc-none/resume").status_code == 404
    assert stub.resumed == []


def test_resume_rejects_finished_incident(api) -> None:
    client, stub = api
    stub.states["inc-done"] = {"done": True, "next": []}
    assert client.post("/incidents/inc-done/resume").status_code == 409
    assert stub.resumed == []


def test_resume_restarts_from_pending_node(api) -> None:
    client, stub = api
    stub.states["inc-halt"] = {"done": False, "next": ["analysis"]}
    response = client.post("/incidents/inc-halt/resume")

    assert response.status_code == 202
    assert response.json()["resumed_from"] == ["analysis"]
    assert stub.resumed == ["inc-halt"]


def test_state_endpoint_maps_404_and_200(api) -> None:
    client, stub = api
    assert client.get("/incidents/inc-none/state").status_code == 404

    stub.states["inc-live"] = {"incident_id": "inc-live", "done": False, "next": ["monitor"]}
    response = client.get("/incidents/inc-live/state")
    assert response.status_code == 200
    assert response.json()["next"] == ["monitor"]


def test_history_endpoint_maps_404_and_200(api) -> None:
    client, stub = api
    assert client.get("/incidents/inc-none/history").status_code == 404

    stub.histories["inc-live"] = [{"step": 1, "next": [], "created_at": "2026-07-20T09:00:00"}]
    response = client.get("/incidents/inc-live/history")
    assert response.status_code == 200
    assert response.json()["checkpoints"][0]["step"] == 1
