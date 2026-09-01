"""도구 인자 검증 — LLM 출력 불신 원칙 (위협 모델 §3 "도구 호출", RT-14·15, 2026-09-01).

PromQL·LogQL 은 LLM 이 자유 생성하는 문자열이라 프롬프트 지시만으로는 임의 질의를 막을 수 없다.
도구 함수 본문에서 결정론적으로 검증한다 — 화이트리스트 밖이면 ValueError (ReAct 루프에서는
ToolMessage 로 모델에 돌아가 자가 수정 대상이 되고, 레드팀 러너는 예외 = 차단으로 판정).

메트릭 화이트리스트는 에이전트 프롬프트가 안내하는 실측 노출 이름(2026-07-17) 기준 —
프롬프트 안내 밖의 메트릭 조회는 정상 시나리오에 없다.
"""

import re

# target-app 관측에 필요한 메트릭 전수 — monitor/analysis 프롬프트 안내분
ALLOWED_METRICS = frozenset(
    {
        "up",
        "http_server_requests_seconds_bucket",
        "http_server_requests_seconds_count",
        "http_server_requests_seconds_sum",
        "http_server_requests_seconds_max",
        "jvm_memory_used_bytes",
        "jvm_memory_max_bytes",
        "jvm_memory_usage_after_gc",
        "jvm_gc_pause_seconds_count",
        "jvm_gc_pause_seconds_sum",
        "jvm_gc_pause_seconds_max",
    }
)

# 집계·비율·추세 판정에 필요한 함수만 — 임의 함수 호출로 검증을 우회하는 표면을 좁힌다
ALLOWED_FUNCTIONS = frozenset(
    {
        "rate",
        "irate",
        "increase",
        "delta",
        "sum",
        "avg",
        "min",
        "max",
        "count",
        "topk",
        "bottomk",
        "histogram_quantile",
        "quantile",
        "abs",
        "round",
        "clamp_max",
        "clamp_min",
        "avg_over_time",
        "max_over_time",
        "min_over_time",
        "sum_over_time",
        "count_over_time",
        "last_over_time",
    }
)

# 식별자이지만 메트릭도 함수도 아닌 PromQL 예약어
_KEYWORDS = frozenset({"by", "without", "on", "ignoring", "group_left", "group_right", "offset", "bool", "and", "or", "unless"})

# 라벨 목록 괄호를 동반하는 예약어 — `by (le, uri)` 의 le·uri 는 라벨 이름이라 검사 제외
_GROUPING_KEYWORDS = frozenset({"by", "without", "on", "ignoring", "group_left", "group_right"})

_STRING = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
_LABEL_MATCHER = re.compile(r"\{[^{}]*\}")
_IDENT = re.compile(r"(?<![\w:])[a-zA-Z_:][\w:]*")

_ALLOWED_LOG_LEVELS = frozenset({"TRACE", "DEBUG", "INFO", "WARN", "ERROR"})

_MAX_MINUTES = 1440  # 24시간 — 정상 시나리오 최장 창(30분)의 여유 상한


def validate_promql(promql: str) -> str:
    """PromQL 의 메트릭·함수를 화이트리스트로 검증한다. 통과 시 원문 그대로 반환."""
    text = _STRING.sub('""', promql)
    text = _LABEL_MATCHER.sub("{}", text)

    # by(...)· without(...) 등 라벨 목록 괄호 구간 수집 — 내부 식별자는 라벨 이름
    label_group_spans: list[tuple[int, int]] = []
    for match in _IDENT.finditer(text):
        if match.group() in _GROUPING_KEYWORDS:
            open_paren = text.find("(", match.end())
            if open_paren != -1 and text[match.end() : open_paren].strip() == "":
                close_paren = text.find(")", open_paren)
                if close_paren != -1:
                    label_group_spans.append((open_paren, close_paren))

    for match in _IDENT.finditer(text):
        token = match.group()
        if token in _KEYWORDS:
            continue
        if any(start <= match.start() < end for start, end in label_group_spans):
            continue
        rest = text[match.end() :].lstrip()
        if rest.startswith("("):
            if token not in ALLOWED_FUNCTIONS:
                raise ValueError(f"허용되지 않은 PromQL 함수: {token} (허용: {', '.join(sorted(ALLOWED_FUNCTIONS))})")
        elif token not in ALLOWED_METRICS:
            raise ValueError(f"허용되지 않은 메트릭: {token} (허용: {', '.join(sorted(ALLOWED_METRICS))})")
    return promql


def validate_minutes(minutes: int) -> int:
    """조회 창 분 단위 범위 검증 — 정수가 아니거나 범위 밖이면 거부."""
    if not isinstance(minutes, int) or isinstance(minutes, bool) or not 1 <= minutes <= _MAX_MINUTES:
        raise ValueError(f"minutes 는 1~{_MAX_MINUTES} 정수여야 한다: {minutes!r}")
    return minutes


def validate_log_level(level: str) -> str:
    """로그 레벨 화이트리스트 — LogQL 문자열에 삽입되는 인자라 임의 문자열은 필터 탈출이 된다 (RT-15)."""
    if level not in _ALLOWED_LOG_LEVELS:
        raise ValueError(f"허용되지 않은 로그 레벨: {level!r} (허용: {', '.join(sorted(_ALLOWED_LOG_LEVELS))})")
    return level
