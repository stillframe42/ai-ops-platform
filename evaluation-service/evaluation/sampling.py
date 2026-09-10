"""샘플링 — 어떤 보고서를 Judge 에 보낼지 결정한다 (docs/quality-evaluation.md §3, ADR-0019).

층화 키는 에이전트 판정 `analysis.severity` 인데 그 값 자체가 평가 대상이라(과대·과소 판정이 곧 D 유형) 판정과
무관한 보조 조건 두 가지를 100% 로 둔다: Alert 규칙의 severity=critical, 사람 승인을 요청한 조치.
결정은 incident_id 해시로 결정론 — 재전달·재실행에서 같은 결정이 나와 at-least-once 중복이 판정을 바꾸지 않는다.
"""

import hashlib
from dataclasses import dataclass
from typing import Literal

Profile = Literal["experiment", "production"]
SampledReason = Literal["p1", "critical", "approval", "random", "skipped"]

# 프로파일별 층화 비율 — 키는 analysis.severity, "default" 는 severity 없음·미지 값
PROFILES: dict[str, dict[str, float]] = {
    "experiment": {"P1": 1.0, "P2": 1.0, "P3": 1.0, "default": 1.0},
    "production": {"P1": 1.0, "P2": 0.30, "P3": 0.10, "default": 0.15},
}
# Alert 규칙에서 labels.severity=critical 인 alertname — 결과 페이로드에 Alert 라벨이 없어 alert_name 으로 판정한다.
# infra/prometheus/rules/target-app-alerts.yml 과 같은 집합을 유지한다 (latency 는 warning)
CRITICAL_ALERT_NAMES = frozenset({"TargetAppHighErrorRate", "TargetAppHeapUsageHigh"})
# approval 블록이 이 상태면 사람 결정을 요청한 것 — skipped 는 NOTIFY_ONLY 뿐(요청 없음)
APPROVAL_REQUESTED_STATUSES = frozenset({"approved", "rejected", "expired"})


@dataclass(frozen=True)
class SamplingDecision:
    sampled: bool
    reason: SampledReason
    rate: float  # 적용 비율 — 보조 조건·P1 은 1.0
    severity: str | None  # 층화 키 값 (analysis.severity)
    profile: str
    detail: str = ""  # 로그·처리 라벨용 (partial / unsampled 등)


def hash_fraction(incident_id: str) -> float:
    """incident_id → [0, 1) 결정론 값. sha256 앞 8바이트 — 파이썬 hash() 는 프로세스마다 시드가 달라 쓰지 않는다."""
    digest = hashlib.sha256(incident_id.encode()).digest()
    return int.from_bytes(digest[:8], "big") / 2**64


class Sampler:
    def __init__(self, profile: Profile) -> None:
        if profile not in PROFILES:
            raise ValueError(f"알 수 없는 샘플링 프로파일: {profile!r} (허용: {sorted(PROFILES)})")
        self.profile = profile
        self._rates = PROFILES[profile]

    def decide(self, report: dict) -> SamplingDecision:
        analysis = report.get("analysis") or {}
        severity = analysis.get("severity")
        # partial 보고서는 분석 블록이 없거나 불완전 — 평가하지 않고 skipped 로 1행 남긴다 (커버리지 계산용)
        if report.get("status") != "completed" or not analysis:
            return SamplingDecision(False, "skipped", 0.0, severity, self.profile, detail="partial")
        if severity == "P1":
            return SamplingDecision(True, "p1", 1.0, severity, self.profile)
        if report.get("alert_name") in CRITICAL_ALERT_NAMES:
            return SamplingDecision(True, "critical", 1.0, severity, self.profile)
        if (report.get("approval") or {}).get("status") in APPROVAL_REQUESTED_STATUSES:
            return SamplingDecision(True, "approval", 1.0, severity, self.profile)
        rate = self._rates.get(severity or "", self._rates["default"])
        if hash_fraction(report["incident_id"]) < rate:
            return SamplingDecision(True, "random", rate, severity, self.profile)
        return SamplingDecision(False, "skipped", rate, severity, self.profile, detail="unsampled")
