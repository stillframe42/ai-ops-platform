"""OTel GenAI 계측이 발신하는 스팬·속성 인벤토리 — 컨벤션 pin 점검용 (docs/otel-genai-mapping.md §7).

계측 라이브러리(openai-v2·util-genai·semconv)나 SDK 버전을 올린 뒤 실행해, 스팬 이름별 속성 키 목록을 §3 표와 diff 한다.
실서비스·네트워크 없이 프로세스 안에서 재현한다: 루프백 HTTP 서버가 게이트웨이(OpenAI 호환 응답 + X-Gateway-* 헤더)
역할을 하고, InMemory exporter 로 스팬을 받는다. 경로 = chat(openai SDK) / invoke_agent(agent_node) / execute_tool(instrumented_tool, MCP 속성 포함).

사용: uv run python scripts/genai_attribute_inventory.py            → 사람이 읽는 표
      uv run python scripts/genai_attribute_inventory.py --json     → 키 목록 JSON — `tests/resources/genai_attribute_inventory.json`
      의 baseline 과 같은 형식. 버전을 올린 뒤 diff 가 나면 §3 표를 갱신하고 baseline 을 교체한다 (`test_otel.py` 스냅샷 테스트가 지킨다)
"""

from __future__ import annotations

import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # 저장소 루트 밖에서 실행해도 app 패키지를 찾도록 (run_redteam 과 같은 관례)

from langchain_core.tools import tool  # noqa: E402
from opentelemetry import trace  # noqa: E402
from opentelemetry.sdk.trace.export import SimpleSpanProcessor  # noqa: E402
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter  # noqa: E402

from app.config.agent_spans import agent_node, instrumented_tool, workflow_span  # noqa: E402
from app.config.otel import setup_telemetry  # noqa: E402

CHAT_COMPLETION = {
    "id": "chatcmpl-inventory",
    "object": "chat.completion",
    "created": 0,
    "model": "claude-sonnet-5",
    "choices": [{"index": 0, "message": {"role": "assistant", "content": "pong"}, "finish_reason": "stop"}],
    "usage": {"prompt_tokens": 11, "completion_tokens": 3, "total_tokens": 14},
}


class _Gateway(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        body = json.dumps(CHAT_COMPLETION).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for header, value in (("X-Gateway-Cache", "miss"), ("X-Gateway-Guardrail", "clean")):
            self.send_header(header, value)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        pass


def _emit_spans(exporter: InMemorySpanExporter) -> None:
    from openai import OpenAI

    server = HTTPServer(("127.0.0.1", 0), _Gateway)
    threading.Thread(target=server.handle_request, daemon=True).start()

    @tool
    def sample_tool(query: str) -> str:
        """인벤토리용 표본 도구"""
        return "ok"

    def node(state: dict) -> dict:
        client = OpenAI(base_url=f"http://127.0.0.1:{server.server_port}/v1", api_key="token", default_headers={"X-Task-Type": "inventory"}, max_retries=0)
        client.chat.completions.with_raw_response.create(model="default", messages=[{"role": "user", "content": "ping"}])
        instrumented_tool(sample_tool).invoke({"query": "q"})
        instrumented_tool(sample_tool, mcp_server_url="http://control-plane:8080/mcp").invoke({"query": "q"})
        return state

    try:
        with workflow_span("inc-inventory"):
            agent_node("inventory", node)({})
    finally:
        server.server_close()


def collect_inventory(exporter: InMemorySpanExporter) -> dict[str, dict]:
    """스팬 이름·kind·scope → 속성 키 목록. exporter 는 전역 provider 에 붙어 있어야 한다 (테스트는 자기 exporter 를 넘긴다)."""
    _emit_spans(exporter)
    inventory: dict[str, dict] = {}
    for span in exporter.get_finished_spans():
        key = f"{span.name} [{span.kind.name}] scope={span.instrumentation_scope.name if span.instrumentation_scope else '?'}"
        inventory[key] = {"attributes": sorted(span.attributes.keys())}
    return dict(sorted(inventory.items()))


def main() -> None:
    setup_telemetry()
    exporter = InMemorySpanExporter()
    trace.get_tracer_provider().add_span_processor(SimpleSpanProcessor(exporter))
    inventory = collect_inventory(exporter)

    if "--json" in sys.argv:
        print(json.dumps(inventory, indent=2, ensure_ascii=False))
        return
    for name, info in inventory.items():
        print(f"\n{name}")
        for attr in info["attributes"]:
            print(f"  - {attr}")


if __name__ == "__main__":
    main()
