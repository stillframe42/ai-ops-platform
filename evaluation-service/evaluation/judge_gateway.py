"""게이트웨이 Judge — llm-gateway `evaluation-judge` 태스크로 채점 (docs/quality-evaluation.md §7, ADR-0019).

호출 규약: `X-Task-Type: evaluation-judge`(모델 선택은 게이트웨이 규칙 — 코드는 모델명을 모른다), `X-Cache-Control: no-cache`
(반복 채점이 시맨틱 캐시에 걸리면 분산이 0), `temperature` 미지정(gpt-5.6-terra 가 기본값 외를 400 으로 거부), OAuth 토큰은
httpx.Auth 가 요청마다 붙인다 (ADR-0016). 보고서 본문은 `wrap_untrusted` 로 감싼다 (ADR-0017 — LLM 출력을 다른 LLM 입력으로).

실패 정책: 게이트웨이 오류(가드레일 block 400·예산·5xx)·타임아웃·응답 파싱 실패는 판정 없음(None) — 스팬에 `error.type` 을
남기고 컨슈머는 발행 없이 정상 종료한다 (커밋 보류 아님: 같은 보고서를 다시 채점해도 같은 실패가 반복될 가능성이 크고,
평가 누락은 샘플링 손실과 같은 등급이다).

스팬: `evaluate incident-report`(INTERNAL, `aiops.operation=evaluate`) ▸ `chat default`(CLIENT, gen_ai.* — openai 계측기가 없어
직접 연다) ▸ httpx `POST`. 보고서에 원 실행 좌표(`trace_ref`)가 있으면 평가 스팬이 `invoke_workflow` 로 link 를 건다 —
소비 스팬이 이미 같은 trace 에 붙지만 link 는 "이 평가의 대상 실행" 을 스팬 단위로 가리킨다 (§6).
"""

from __future__ import annotations

import logging
from urllib.parse import urlparse

import httpx
from opentelemetry import trace
from opentelemetry.trace import Link, SpanContext, SpanKind, Status, StatusCode, TraceFlags

from evaluation.config.otel_evaluation import (
    ATTR_EXPERIMENT_NAME,
    ATTR_EXPERIMENT_VARIANT,
    ATTR_FAILURE_MODE,
    ATTR_JUDGE_MODEL,
    ATTR_JUDGE_PROMPT_VERSION,
    ATTR_LINK_REASON,
    ATTR_LOW_QUALITY,
    ATTR_NORMALIZED,
    ATTR_OPERATION,
    LINK_REASON,
    record_evaluation,
    record_judge_call,
)
from evaluation.config.otel_genai import GEN_AI_OPERATION_NAME, OPERATION_CHAT, incident_attributes
from evaluation.config.settings import Settings
from evaluation.judge import Evaluation, VerdictError, parse_verdict
from evaluation.judge_prompt import DEFAULT_PROMPT_VERSION, build_user_prompt, load_system_prompt
from evaluation.tools.oauth_client import shared_auth

logger = logging.getLogger(__name__)

TASK_TYPE = "evaluation-judge"
# 게이트웨이 별칭 — 실모델은 응답 `model` 필드(폴백·다운그레이드 반영)로 안다
REQUEST_MODEL = "default"
# Judge 1회 상한 — 재조회 요약 + 보고서 ≈ 3~4k 토큰 입력, 응답은 JSON 한 덩어리 (2026-09-09 예비 실측 평균 20~40s)
JUDGE_TIMEOUT = httpx.Timeout(180.0, connect=5.0)
ATTR_ERROR_TYPE = "error.type"

_tracer = trace.get_tracer("evaluation-service")


def _link_to(trace_ref: dict | None) -> list[Link]:
    """보고서 `trace_ref`({trace_id, span_id} hex) → 원 실행 워크플로 스팬 link. 없거나 형식이 틀리면 link 없음."""
    if not isinstance(trace_ref, dict):
        return []
    try:
        trace_id, span_id = int(str(trace_ref["trace_id"]), 16), int(str(trace_ref["span_id"]), 16)
    except (KeyError, TypeError, ValueError):
        return []
    context = SpanContext(trace_id, span_id, is_remote=True, trace_flags=TraceFlags(TraceFlags.SAMPLED))
    return [Link(context, attributes={ATTR_LINK_REASON: LINK_REASON})]


def _error_type(exc: Exception) -> str:
    if isinstance(exc, httpx.HTTPStatusError):
        return f"http.{exc.response.status_code}"
    if isinstance(exc, httpx.TimeoutException):
        return "timeout"
    return type(exc).__name__


class GatewayJudge:
    def __init__(
        self,
        settings: Settings,
        *,
        prompt_version: str = DEFAULT_PROMPT_VERSION,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout: httpx.Timeout = JUDGE_TIMEOUT,
    ) -> None:
        self._base_url = settings.llm_base_url.rstrip("/")
        self._prompt_version = prompt_version
        self._system_prompt = load_system_prompt(prompt_version)
        self._client = httpx.AsyncClient(auth=shared_auth(settings), timeout=timeout, transport=transport)
        parsed = urlparse(self._base_url)
        self._server_attributes = {"server.address": parsed.hostname or "", "server.port": parsed.port or 80}

    @property
    def prompt_version(self) -> str:
        return self._prompt_version

    async def aclose(self) -> None:
        await self._client.aclose()

    async def evaluate(self, report: dict, evidence: dict | None, *, ground_truth: str | None = None) -> Evaluation | None:
        """ground_truth 는 골든셋 측정 전용 — 온라인 경로(컨슈머)는 주지 않는다 (배포 형상 그대로 측정하려면 여기서도 생략)."""
        incident_id = report["incident_id"]
        analysis = report.get("analysis") or {}
        experiment = report.get("experiment") or {}
        attributes = {
            ATTR_OPERATION: "evaluate",
            ATTR_JUDGE_PROMPT_VERSION: self._prompt_version,
            **incident_attributes(incident_id),
        }
        # 스팬은 메트릭과 달리 시리즈 형태 제약이 없다 — 실험 밖 보고서에는 속성 자체를 두지 않는다
        for attribute, key in ((ATTR_EXPERIMENT_NAME, "name"), (ATTR_EXPERIMENT_VARIANT, "variant")):
            if experiment.get(key):
                attributes[attribute] = str(experiment[key])
        with _tracer.start_as_current_span(
            "evaluate incident-report", kind=SpanKind.INTERNAL, attributes=attributes, links=_link_to(report.get("trace_ref"))
        ) as span:
            try:
                content, response_model, response_id = await self._chat(build_user_prompt(report, evidence, ground_truth))
                verdict = parse_verdict(content)
            except (httpx.HTTPError, VerdictError) as exc:
                span.record_exception(exc)
                span.set_status(Status(StatusCode.ERROR, _error_type(exc)))
                span.set_attribute(ATTR_ERROR_TYPE, _error_type(exc))
                record_judge_call(error_type=_error_type(exc))
                logger.warning("Judge 판정 실패 — 건너뜀 (%s): %s", incident_id, exc)
                return None
            record_judge_call(error_type=None)

            evaluation = Evaluation(
                incident_id=incident_id,
                scores=verdict.scores,
                failure_mode=verdict.failure_mode,
                judge_model=response_model,
                prompt_version=self._prompt_version,
                evidence_available=evidence is not None,
                analysis_prompt_version=analysis.get("prompt_version"),
                experiment_name=experiment.get("name"),
                experiment_variant=experiment.get("variant"),
                judge_response_id=response_id,
            )
            span.set_attributes(
                {
                    ATTR_JUDGE_MODEL: response_model,
                    ATTR_FAILURE_MODE: evaluation.failure_mode,
                    ATTR_LOW_QUALITY: evaluation.low_quality,
                    ATTR_NORMALIZED: verdict.normalized,
                }
            )
            record_evaluation(evaluation, severity=analysis.get("severity"), span=span)
            return evaluation

    async def _chat(self, user_prompt: str) -> tuple[str, str, str | None]:
        """게이트웨이 채팅 1회 → (본문, 실모델, 응답 id). `chat default` CLIENT 스팬 — 게이트웨이 판정 헤더는 httpx 훅이 이 스팬에 올린다."""
        body = {
            "model": REQUEST_MODEL,
            "messages": [
                {"role": "system", "content": self._system_prompt},
                {"role": "user", "content": user_prompt},
            ],
        }
        attributes = {
            GEN_AI_OPERATION_NAME: OPERATION_CHAT,
            "gen_ai.provider.name": "openai",  # OpenAI 호환 엔드포인트 — 실프로바이더는 게이트웨이 스팬 몫 (mapping §5)
            "gen_ai.request.model": REQUEST_MODEL,
            **self._server_attributes,
        }
        with _tracer.start_as_current_span(f"chat {REQUEST_MODEL}", kind=SpanKind.CLIENT, attributes=attributes) as span:
            response = await self._client.post(
                f"{self._base_url}/chat/completions",
                json=body,
                headers={"X-Task-Type": TASK_TYPE, "X-Cache-Control": "no-cache"},
            )
            response.raise_for_status()
            data = response.json()
            model = str(data.get("model") or REQUEST_MODEL)
            response_id = data.get("id")
            span.set_attribute("gen_ai.response.model", model)
            if response_id:
                span.set_attribute("gen_ai.response.id", str(response_id))
            usage = data.get("usage") or {}
            for key, attribute in (("prompt_tokens", "gen_ai.usage.input_tokens"), ("completion_tokens", "gen_ai.usage.output_tokens")):
                if isinstance(usage.get(key), int):
                    span.set_attribute(attribute, usage[key])
            choices = data.get("choices") or []
            if not choices:
                raise VerdictError("choices 없음")
            finish = choices[0].get("finish_reason")
            if finish:
                span.set_attribute("gen_ai.response.finish_reasons", [str(finish)])
            content = (choices[0].get("message") or {}).get("content")
            if not isinstance(content, str):
                raise VerdictError(f"본문 없음 (content={type(content).__name__})")
            return content, model, str(response_id) if response_id else None
