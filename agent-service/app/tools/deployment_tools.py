"""배포 이력 조회 도구 — 더미 데이터 (배포 이력 시스템은 이번 주 범위 밖).

분석 에이전트가 "최근 배포와 장애의 상관"을 배제/확인하는 용도. 더미는 장애와 무관한
과거 배포 1건만 반환해 "최근 배포 없음 → 배포 원인 배제" 추론이 성립하게 한다.
"""

import json

from langchain_core.tools import tool

# 실제 배포 이력 연동 전까지의 고정 더미 — target-app 최초 기동 시점 기준
_DUMMY_DEPLOYMENTS = [
    {
        "service": "target-app",
        "version": "0.0.1-SNAPSHOT",
        "deployed_at": "2026-07-14T10:00:00+09:00",
        "change_summary": "초기 배포 (데모 앱)",
    }
]


@tool
def get_recent_deployments() -> str:
    """최근 배포 이력을 반환한다 — 장애 시점과 배포의 상관을 확인/배제하는 용도."""
    return json.dumps(_DUMMY_DEPLOYMENTS, ensure_ascii=False)
