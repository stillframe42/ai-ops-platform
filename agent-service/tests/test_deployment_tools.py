"""deployment_tools 단위 테스트 — DAY 10 시점에는 더미 데이터 (배포 이력 시스템은 범위 밖)."""

import json

from app.tools import deployment_tools


def test_get_recent_deployments_returns_dummy_history():
    out = deployment_tools.get_recent_deployments.invoke({})

    deployments = json.loads(out)
    assert len(deployments) >= 1
    # 분석 에이전트가 "최근 배포와 장애의 상관"을 따질 수 있는 최소 필드
    for entry in deployments:
        assert set(entry) >= {"service", "version", "deployed_at"}
    assert any(entry["service"] == "target-app" for entry in deployments)
