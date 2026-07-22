"""Langfuse 트레이싱 연동 테스트 — 키-게이트와 세션 연결 규약만 검증한다 (DAY 14 몫).

실 Langfuse 전송은 실측에서 — 여기서는 "키 없으면 완전 비활성, 있으면 실행 config 에
콜백 + 세션 id(=thread_id=incident id) 가 주입된다"는 계약을 고정한다.
"""

from langgraph.checkpoint.memory import InMemorySaver

from app.config.settings import Settings
from app.config.tracing import build_langfuse_handler
from app.supervisor.graph import build_graph
from app.supervisor.runtime import GraphRuntime


def _settings(**overrides) -> Settings:
    # .env 미로딩 생성 — 테스트가 로컬 .env 값에 오염되지 않게 한다
    return Settings(_env_file=None, **overrides)


def test_handler_disabled_without_keys():
    """키 3종이 모두 있어야 활성 — 하나라도 없으면 None (트레이싱 완전 비활성)."""
    assert build_langfuse_handler(_settings()) is None
    assert (
        build_langfuse_handler(
            _settings(langfuse_host="http://localhost:3000", langfuse_public_key="pk")
        )
        is None
    )


def test_config_injects_session_and_callbacks_when_tracer_present():
    """트레이서가 있으면 실행 config 에 콜백 + langfuse_session_id(=인시던트 id) 주입."""
    tracer = object()  # 콜백 규약만 검증 — 실 핸들러는 실측에서
    runtime = GraphRuntime(build_graph(checkpointer=InMemorySaver()), tracer=tracer)

    config = runtime._config("inc-trace-001")

    assert config["callbacks"] == [tracer]
    assert config["metadata"]["langfuse_session_id"] == "inc-trace-001"
    assert config["configurable"]["thread_id"] == "inc-trace-001"  # 기존 계약 유지


def test_config_unchanged_without_tracer():
    """트레이서가 없으면 config 는 기존 그대로 — 콜백/메타데이터 키 자체가 없다."""
    runtime = GraphRuntime(build_graph(checkpointer=InMemorySaver()))

    config = runtime._config("inc-trace-002")

    assert "callbacks" not in config
    assert "metadata" not in config
