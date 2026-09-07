"""ops.incidents 컨슈머 (DAY 18, ADR-0011) — Alert → 그래프 자동 트리거의 유입 계층.

처리 로직(IncidentEventProcessor)과 aiokafka 배선(run_incident_consumer)을 분리한다 —
로직은 fake 주입으로 단위 테스트, 브로커 왕복은 E2E 에서.

커밋 전략 (at-least-once): getmany 배치 → 동시 처리(Semaphore) → 배치 전체 완료 후 commit.
인프라 실패(결과 발행 불가)는 예외로 올라와 커밋을 보류하고 seek_to_committed 로 재수신한다 —
미커밋이어도 같은 세션에선 재전달되지 않는 aiokafka 특성 때문에 되감기가 명시적으로 필요하다.
중복 재전달은 처리 측 멱등(done 검사 + Durable Execution 재개)이 흡수한다.
"""

import asyncio
import json
import logging
from datetime import UTC, datetime

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from app.config.settings import Settings
from app.events.consumer_loop import consume_batches, start_with_backoff
from app.events.propagation import KafkaHeaders, consumer_span
from app.events.publishing import make_publisher
from app.supervisor.runtime import INCIDENT_PRESETS, GraphRuntime
from app.supervisor.state import IncidentInfo

logger = logging.getLogger(__name__)

TOPIC_INCIDENTS = "ops.incidents"
TOPIC_ANALYSIS_RESULTS = "ops.analysis.results"
TOPIC_ACTIONS_PENDING = "ops.actions.pending"
CONSUMER_GROUP_ID = "agent-service"
# 동시 그래프 실행 상한 — 인시던트 1건 = LLM 호출 다수라 비용·자원 보호가 목적
MAX_CONCURRENT_RUNS = 3


class IncidentEventProcessor:
    """메시지 1건 처리 — 파싱 → 멱등 판정 → 그래프 실행 → 결과 발행.

    반환값은 처리 결과 라벨 (로그·테스트용). 건너뜀은 정상 종료다 — poison pill 이
    커밋을 막으면 파티션 전체가 멈춘다. 예외 전파는 인프라 실패(커밋 보류 신호)뿐.
    """

    def __init__(
        self,
        runtime: GraphRuntime,
        publish_result,
        publish_approval,
        max_concurrent: int = MAX_CONCURRENT_RUNS,
    ):
        self.runtime = runtime
        self.publish_result = publish_result  # async (key: str, payload: dict) -> None
        self.publish_approval = publish_approval  # async (key: str, payload: dict) -> None
        self._semaphore = asyncio.Semaphore(max_concurrent)
        # 같은 인시던트의 동시 처리 방지 — 배치 재수신·수동 트리거와의 겹침 대비
        self._active: set[str] = set()

    async def process(self, raw: bytes, headers: KafkaHeaders | None = None) -> str:
        # `ops.incidents process` CONSUMER 스팬 (DAY 43) — 부모는 control-plane 발행 헤더, 워크플로·후속 발행이 자식
        with consumer_span(TOPIC_INCIDENTS, headers):
            return await self._process(raw)

    async def _process(self, raw: bytes) -> str:
        event = self._parse(raw)
        if event is None:
            return "skipped:malformed"
        incident_id = event.get("incident_id")
        scenario = event.get("scenario")
        if not incident_id or not isinstance(incident_id, str):
            logger.warning("incident_id 없는 이벤트 — 건너뜀: %s", event)
            return "skipped:no-incident-id"
        if scenario not in INCIDENT_PRESETS:
            logger.warning("미지 scenario '%s' — 건너뜀 (%s)", scenario, incident_id)
            return "skipped:unknown-scenario"
        if incident_id in self._active:
            logger.info("처리 중인 인시던트 %s — 건너뜀", incident_id)
            return "skipped:running"

        self._active.add(incident_id)
        try:
            return await self._run_and_publish(event, incident_id)
        finally:
            self._active.discard(incident_id)

    async def _run_and_publish(self, event: dict, incident_id: str) -> str:
        state = await self.runtime.get_state(incident_id)
        if state is not None and state["done"]:
            # 이미 완주 — 재실행 없이 결과만 재발행: 이전 사이클에서 결과 발행 실패 후
            # 재전달됐을 가능성에 대비한다 (at-least-once 의 짝은 소비 측 멱등)
            await self.publish_result(incident_id, await self.runtime.get_result(incident_id))
            logger.info("완주된 인시던트 %s — 결과만 재발행", incident_id)
            return "skipped:already-done"

        async with self._semaphore:
            try:
                if state is None:
                    # 현재 스팬 = 소비 스팬(상류 웹훅 trace) — 워크플로가 그 아래 붙는다
                    await self.runtime.start(self._to_incident(event))
                else:
                    # 미완 체크포인트 = 처리 중 다운 후 재전달 — 완료 노드를 반복하지 않는
                    # Durable Execution 재개 경로 (오프셋 층과 체크포인터 층의 결합 지점)
                    logger.info("미완 체크포인트 재개 — %s", incident_id)
                    await self.runtime.resume(incident_id)
            except Exception:
                # 그래프 실패도 발행으로 이어간다 — 부분 보고서(DAY 13)가 결과물이다
                logger.exception("인시던트 %s 그래프 실행 실패 — 부분 결과 발행", incident_id)

        approval_request = await self.runtime.get_pending_approval(incident_id)
        if approval_request is not None:
            # interrupt 정지 = 승인 대기 (ADR-0005) — 승인 요청서만 pending 으로 발행하고
            # 보고서는 보류한다: 종결 보고 발행은 decisions 소비 측 책임. 재전달로 이 분기에
            # 다시 와도 발행은 반복되지만 소비 측 upsert 멱등(부분 유니크 인덱스)이 흡수한다
            await self.publish_approval(incident_id, approval_request)
            logger.info("승인 대기 — ops.actions.pending 발행 (%s)", incident_id)
            return "processed:awaiting-approval"

        await self.publish_result(incident_id, await self.runtime.get_result(incident_id))
        return "processed"

    @staticmethod
    def _parse(raw: bytes) -> dict | None:
        try:
            event = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            logger.warning("파싱 불가 메시지 — 건너뜀 (%d bytes)", len(raw))
            return None
        return event if isinstance(event, dict) else None

    @staticmethod
    def _to_incident(event: dict) -> IncidentInfo:
        """이벤트 값 우선 구성 — 프리셋의 "(수동 트리거)" 문구는 Kafka 경로에 쓰지 않는다."""
        scenario = event["scenario"]
        alert_name = event.get("alert_name") or INCIDENT_PRESETS[scenario][0]
        return IncidentInfo(
            id=event["incident_id"],
            scenario=scenario,
            alert_name=alert_name,
            summary=event.get("summary") or f"{alert_name} 발화 (Kafka 이벤트)",
            occurred_at=event.get("occurred_at")
            or event.get("starts_at")
            or datetime.now(UTC).isoformat(),
        )


async def run_incident_consumer(settings: Settings, runtime: GraphRuntime) -> None:
    """컨슈머 수명 루프 — lifespan 태스크로 소유되고 취소로 종료된다."""
    producer = AIOKafkaProducer(bootstrap_servers=settings.kafka_bootstrap_servers)
    consumer = AIOKafkaConsumer(
        TOPIC_INCIDENTS,
        bootstrap_servers=settings.kafka_bootstrap_servers,
        group_id=CONSUMER_GROUP_ID,
        enable_auto_commit=False,  # 처리 완료 후 수동 커밋 — 유실보다 중복을 택한다
        auto_offset_reset="earliest",  # 그룹 최초 기동 시 다운 중 발행분까지 소급 소비
    )

    # key=incident_id 관례는 호출 측(process)이 지킨다 — 발행 클로저는 직렬화만 담당
    publish_result = make_publisher(producer, TOPIC_ANALYSIS_RESULTS)
    publish_approval = make_publisher(producer, TOPIC_ACTIONS_PENDING)
    processor = IncidentEventProcessor(runtime, publish_result, publish_approval)

    await start_with_backoff(consumer, producer, settings.kafka_bootstrap_servers)
    logger.info("Kafka 컨슈머 시작 — %s (%s)", TOPIC_INCIDENTS, settings.kafka_bootstrap_servers)
    try:
        await consume_batches(
            consumer, processor.process, max_records=MAX_CONCURRENT_RUNS, label="incidents"
        )
    finally:
        await consumer.stop()
        await producer.stop()
