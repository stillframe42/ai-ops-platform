"""ops.analysis.results 컨슈머 — 종결 보고서 → 샘플링 → 시간창 재조회 → Judge → ops.evaluation.results (ADR-0019).

처리 로직(EvaluationEventProcessor)과 aiokafka 배선(run_results_consumer)을 분리한다 — agent-service 컨슈머와 같은 관례.
컨슈머 그룹은 control-plane(`control-plane`)·agent-service 와 다른 `evaluation-service` — 같은 토픽을 독립 오프셋으로 읽으므로
응답 경로(인시던트 처리·보고서 저장)에 영향이 없다.

커밋 규약 (at-least-once): 건너뜀(파싱 불가·미샘플·partial)은 정상 종료, 인프라 실패(평가 발행 불가)만 예외 전파 = 커밋 보류.
재조회 실패는 인프라 실패로 보지 않는다 — 근거 없이도 보고서 내부 정합 판정은 가능하므로(§4 보존 밖 규칙) 근거 없음으로 진행한다.
중복 재전달은 샘플링 결정론(같은 id = 같은 결정)과 저장 측 멱등(control-plane)이 흡수한다.
"""

import asyncio
import json
import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from evaluation.config.otel_genai import incident_attributes
from evaluation.config.settings import Settings
from evaluation.events.consumer_loop import consume_batches, start_with_backoff
from evaluation.events.propagation import KafkaHeaders, consumer_span
from evaluation.events.publishing import make_publisher
from evaluation.evidence import EvidenceCollector, is_empty
from evaluation.judge import Judge
from evaluation.judge_gateway import GatewayJudge
from evaluation.sampling import Sampler, SamplingDecision

logger = logging.getLogger(__name__)

TOPIC_ANALYSIS_RESULTS = "ops.analysis.results"
TOPIC_EVALUATION_RESULTS = "ops.evaluation.results"
CONSUMER_GROUP_ID = "evaluation-service"
# 동시 평가 상한 — 평가 1건 = 재조회 6질의 + Judge 호출 1회, 게이트웨이·Prometheus 보호
MAX_CONCURRENT_EVALUATIONS = 2

# 샘플링·근거 판정을 소비 스팬 속성으로 — 자체 확장 네임스페이스 (docs/quality-evaluation.md §6, 표준에 없는 어휘)
ATTR_SAMPLED = "aiops.evaluation.sampled"
ATTR_SAMPLED_REASON = "aiops.evaluation.sampled_reason"
ATTR_SAMPLE_RATE = "aiops.evaluation.sample_rate"
ATTR_SAMPLE_PROFILE = "aiops.evaluation.sample_profile"
ATTR_EVIDENCE = "aiops.evaluation.evidence"  # collected | empty | unavailable | disabled


class EvaluationEventProcessor:
    """메시지 1건 처리 — 파싱 → 샘플링 → (근거 재조회 → Judge → 발행). 반환값은 처리 결과 라벨 (로그·테스트용)."""

    def __init__(
        self,
        sampler: Sampler,
        judge: Judge,
        publish_evaluation,
        evidence: EvidenceCollector | None = None,
        max_concurrent: int = MAX_CONCURRENT_EVALUATIONS,
    ) -> None:
        self.sampler = sampler
        self.judge = judge
        self.publish_evaluation = publish_evaluation  # async (key: str, payload: dict) -> None
        self.evidence = evidence  # None = 재조회 비활성 (스택 없는 환경)
        self._semaphore = asyncio.Semaphore(max_concurrent)

    async def process(self, raw: bytes, headers: KafkaHeaders | None = None) -> str:
        # `ops.analysis.results process` CONSUMER 스팬 — 부모는 agent-service 발행 헤더 → 평가가 인시던트 trace 에 붙는다
        with consumer_span(TOPIC_ANALYSIS_RESULTS, headers) as span:
            return await self._process(raw, span)

    async def _process(self, raw: bytes, span) -> str:
        try:
            report = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            logger.warning("파싱 불가 보고서 메시지 — 건너뜀 (%d bytes)", len(raw))
            return "skipped:malformed"
        if not isinstance(report, dict):
            return "skipped:malformed"
        incident_id = report.get("incident_id")
        if not incident_id or not isinstance(incident_id, str):
            logger.warning("incident_id 없는 보고서 — 건너뜀")
            return "skipped:no-incident-id"
        span.set_attributes(incident_attributes(incident_id))

        decision = self.sampler.decide(report)
        self._record_decision(span, decision)
        logger.info(
            "샘플링 결정 — %s sampled=%s reason=%s rate=%.2f severity=%s profile=%s%s",
            incident_id,
            decision.sampled,
            decision.reason,
            decision.rate,
            decision.severity,
            decision.profile,
            f" ({decision.detail})" if decision.detail else "",
        )
        if not decision.sampled:
            return f"skipped:{decision.detail or decision.reason}"

        async with self._semaphore:
            evidence = await self._collect_evidence(report, span)
            evaluation = await self.judge.evaluate(report, evidence)
        if evaluation is None:
            return "sampled:judge-skipped"  # 판정 없음 — Judge 실패(스팬 error.type)·구현 전 스텁
        # 발행 실패는 인프라 실패 — 예외 전파로 커밋을 보류한다
        await self.publish_evaluation(incident_id, evaluation.to_payload())
        logger.info("평가 발행 — %s failure_mode=%s low_quality=%s", incident_id, evaluation.failure_mode, evaluation.low_quality)
        return "evaluated"

    @staticmethod
    def _record_decision(span, decision: SamplingDecision) -> None:
        span.set_attribute(ATTR_SAMPLED, decision.sampled)
        span.set_attribute(ATTR_SAMPLED_REASON, decision.reason)
        span.set_attribute(ATTR_SAMPLE_RATE, decision.rate)
        span.set_attribute(ATTR_SAMPLE_PROFILE, decision.profile)

    async def _collect_evidence(self, report: dict, span) -> dict | None:
        if self.evidence is None:
            span.set_attribute(ATTR_EVIDENCE, "disabled")
            return None
        try:
            evidence = await self.evidence.collect(report["incident_id"], report.get("completed_at"))
        except Exception as exc:  # noqa: BLE001 — 재조회 실패는 근거 없음으로 진행 (커밋 보류 아님)
            logger.warning("시간창 재조회 실패 — 근거 없이 진행 (%s): %r", report["incident_id"], exc)
            span.set_attribute(ATTR_EVIDENCE, "unavailable")
            return None
        if is_empty(evidence):
            span.set_attribute(ATTR_EVIDENCE, "empty")
            return None
        span.set_attribute(ATTR_EVIDENCE, "collected")
        return evidence


def build_processor(settings: Settings, publish_evaluation, judge: Judge | None = None) -> EvaluationEventProcessor:
    """설정 → 처리기 조립. Judge 미지정 = 게이트웨이 Judge (프롬프트 버전은 설정)."""
    evidence = (
        EvidenceCollector(settings.prometheus_url, settings.loki_url) if settings.eval_evidence_enabled else None
    )
    return EvaluationEventProcessor(
        Sampler(settings.eval_sample_profile),
        judge or GatewayJudge(settings, prompt_version=settings.eval_judge_prompt_version),
        publish_evaluation,
        evidence=evidence,
    )


async def run_results_consumer(settings: Settings, judge: Judge | None = None) -> None:
    """컨슈머 수명 루프 — lifespan 태스크로 소유되고 취소로 종료된다."""
    producer = AIOKafkaProducer(bootstrap_servers=settings.kafka_bootstrap_servers)
    consumer = AIOKafkaConsumer(
        TOPIC_ANALYSIS_RESULTS,
        bootstrap_servers=settings.kafka_bootstrap_servers,
        group_id=CONSUMER_GROUP_ID,
        enable_auto_commit=False,  # 처리 완료 후 수동 커밋 — 유실보다 중복을 택한다
        auto_offset_reset="earliest",  # 그룹 최초 기동 시 보존분까지 소급 — 샘플링이 평가 비용 상한을 잡는다
    )
    processor = build_processor(settings, make_publisher(producer, TOPIC_EVALUATION_RESULTS), judge)

    await start_with_backoff(consumer, producer, settings.kafka_bootstrap_servers)
    logger.info(
        "Kafka 컨슈머 시작 — %s (%s, profile=%s, evidence=%s)",
        TOPIC_ANALYSIS_RESULTS,
        settings.kafka_bootstrap_servers,
        settings.eval_sample_profile,
        settings.eval_evidence_enabled,
    )
    try:
        await consume_batches(consumer, processor.process, max_records=10, label="analysis-results")
    finally:
        await consumer.stop()
        await producer.stop()
