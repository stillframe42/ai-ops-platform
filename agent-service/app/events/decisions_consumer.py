"""ops.actions.decisions 컨슈머 (ADR-0005) — 승인 결정 → 그래프 재개의 유입 계층.

처리 로직(DecisionEventProcessor)과 aiokafka 배선(run_decisions_consumer)을 분리한다 —
incident_consumer 와 같은 관례. 승인 대기 시점에는 보고서 발행을 보류하므로, 재개 완료 후
최종 보고서를 ops.analysis.results 로 발행하는 책임이 이쪽에 있다.

컨슈머 그룹은 incident 컨슈머와 분리한다 — 같은 그룹이 서로 다른 토픽을 구독하면
리밸런스가 얽힌다.
"""

import json
import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from app.config.settings import Settings
from app.events.consumer_loop import consume_batches, start_with_backoff
from app.events.propagation import KafkaHeaders, consumer_span
from app.events.publishing import make_publisher
from app.events.incident_consumer import TOPIC_ANALYSIS_RESULTS
from app.supervisor.runtime import GraphRuntime

logger = logging.getLogger(__name__)

TOPIC_ACTIONS_DECISIONS = "ops.actions.decisions"
DECISIONS_GROUP_ID = "agent-service-decisions"

VALID_STATUSES = ("approved", "rejected", "expired")


class DecisionEventProcessor:
    """결정 1건 처리 — 파싱 → 검증 → resume → 최종 보고서 발행.

    반환값은 처리 결과 라벨 (로그·테스트용). 건너뜀은 정상 종료다 — poison 결정이
    인시던트를 잘못 종결시키거나 커밋을 막으면 안 된다. 예외 전파는 인프라 실패
    (보고서 발행 불가 — 커밋 보류 신호)뿐.
    """

    def __init__(self, runtime: GraphRuntime, publish_result):
        self.runtime = runtime
        self.publish_result = publish_result  # async (key: str, payload: dict) -> None

    async def process(self, raw: bytes, headers: KafkaHeaders | None = None) -> str:
        # `ops.actions.decisions process` CONSUMER 스팬 (DAY 43) — 부모는 승인 API → control-plane 발행 헤더
        with consumer_span(TOPIC_ACTIONS_DECISIONS, headers):
            return await self._process(raw)

    async def _process(self, raw: bytes) -> str:
        try:
            event = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            logger.warning("파싱 불가 결정 메시지 — 건너뜀 (%d bytes)", len(raw))
            return "skipped:malformed"
        if not isinstance(event, dict):
            return "skipped:malformed"
        incident_id = event.get("incident_id")
        if not incident_id or not isinstance(incident_id, str):
            logger.warning("incident_id 없는 결정 — 건너뜀: %s", event)
            return "skipped:no-incident-id"
        if event.get("status") not in VALID_STATUSES:
            # 불량 status 로 resume 하면 안전 측 거부로 종결돼 버린다 — 재개 전에 차단
            logger.warning("불량 status 결정 — 건너뜀 (%s): %r", incident_id, event.get("status"))
            return "skipped:invalid-status"

        state = await self.runtime.get_state(incident_id)
        if state is None:
            logger.warning("모르는 인시던트의 결정 — 건너뜀 (%s)", incident_id)
            return "skipped:unknown-incident"
        if state["done"]:
            # 재개 후 발행 실패 → 재전달 시나리오 — 보고서 재발행이 없으면 영영 발행되지 않는다
            await self.publish_result(incident_id, await self.runtime.get_result(incident_id))
            logger.info("이미 종결된 인시던트 %s — 보고서만 재발행", incident_id)
            return "skipped:already-done"
        if not state.get("awaiting_approval"):
            logger.warning("승인 대기가 아닌 인시던트 %s — 건너뜀 (실행 중 그래프에 결정 주입 금지)", incident_id)
            return "skipped:not-waiting"

        try:
            # 현재 스팬 = 소비 스팬(승인 API trace) — 재개 워크플로가 그 아래 붙고 원 실행은 link
            await self.runtime.resume_with_decision(incident_id, event)
        except Exception:
            # 재개 실패는 삼킨다 — 부분 보고서 발행(DAY 13 관례)으로 이어간다
            logger.exception("인시던트 %s 결정 재개 실패 — 부분 보고서 발행", incident_id)

        await self.publish_result(incident_id, await self.runtime.get_result(incident_id))
        logger.info("승인 결정 반영 — %s (%s)", incident_id, event["status"])
        return "processed"


async def run_decisions_consumer(settings: Settings, runtime: GraphRuntime) -> None:
    """컨슈머 수명 루프 — lifespan 태스크로 소유되고 취소로 종료된다."""
    producer = AIOKafkaProducer(bootstrap_servers=settings.kafka_bootstrap_servers)
    consumer = AIOKafkaConsumer(
        TOPIC_ACTIONS_DECISIONS,
        bootstrap_servers=settings.kafka_bootstrap_servers,
        group_id=DECISIONS_GROUP_ID,
        enable_auto_commit=False,  # 처리 완료 후 수동 커밋 — 유실보다 중복을 택한다
        auto_offset_reset="earliest",  # 그룹 최초 기동 시 다운 중 발행분까지 소급 소비
    )

    processor = DecisionEventProcessor(runtime, make_publisher(producer, TOPIC_ANALYSIS_RESULTS))

    await start_with_backoff(consumer, producer, settings.kafka_bootstrap_servers)
    logger.info(
        "Kafka 컨슈머 시작 — %s (%s)", TOPIC_ACTIONS_DECISIONS, settings.kafka_bootstrap_servers
    )
    try:
        await consume_batches(consumer, processor.process, max_records=10, label="decisions")
    finally:
        await consumer.stop()
        await producer.stop()
