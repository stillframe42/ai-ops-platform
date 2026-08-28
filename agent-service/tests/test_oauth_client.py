"""ClientCredentialsAuth 단위 테스트 — httpx.MockTransport 로 토큰 엔드포인트·리소스 서버를 가장한다.

검증 대상: 최초 발급·캐시 재사용, 만료 60초 전 선제 재발급, 401 수신 시 재발급 후 1회 재시도,
client_secret_basic 규약. 시계는 monotonic 을 monkeypatch 해 실대기 없이 만료를 재현한다.
"""

import base64
import json

import httpx
import pytest

from app.tools import oauth_client
from app.tools.oauth_client import ClientCredentialsAuth

TOKEN_URL = "http://auth:8091/oauth2/token"
RESOURCE_URL = "http://cp:8080/mcp"


class FakeServers:
    """토큰 서버 + 리소스 서버 한 몸 — 발급 횟수와 리소스 호출의 bearer 를 기록한다."""

    def __init__(self, expires_in: int = 900, reject_tokens: set[str] | None = None):
        self.issued = 0
        self.expires_in = expires_in
        self.reject_tokens = reject_tokens or set()
        self.token_requests: list[httpx.Request] = []
        self.bearers: list[str | None] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        if str(request.url) == TOKEN_URL:
            self.issued += 1
            self.token_requests.append(request)
            return httpx.Response(
                200, json={"access_token": f"tok-{self.issued}", "expires_in": self.expires_in, "token_type": "Bearer"}
            )
        bearer = request.headers.get("Authorization")
        self.bearers.append(bearer)
        token = bearer.removeprefix("Bearer ") if bearer else None
        if token in self.reject_tokens:
            return httpx.Response(401, headers={"WWW-Authenticate": "Bearer"})
        return httpx.Response(200, json={"ok": True})


def _auth() -> ClientCredentialsAuth:
    return ClientCredentialsAuth(TOKEN_URL, "agent-service", "secret", "ops:read llm:invoke")


async def _call(auth, servers) -> httpx.Response:
    async with httpx.AsyncClient(transport=httpx.MockTransport(servers.handler), auth=auth) as client:
        return await client.post(RESOURCE_URL, json={})


@pytest.mark.anyio
async def test_first_call_issues_token_and_reuses_it():
    servers = FakeServers()
    auth = _auth()

    r1 = await _call(auth, servers)
    r2 = await _call(auth, servers)

    assert r1.status_code == r2.status_code == 200
    assert servers.issued == 1
    assert servers.bearers == ["Bearer tok-1", "Bearer tok-1"]


@pytest.mark.anyio
async def test_token_request_uses_client_secret_basic():
    servers = FakeServers()
    await _call(_auth(), servers)

    req = servers.token_requests[0]
    expected = base64.b64encode(b"agent-service:secret").decode()
    assert req.headers["Authorization"] == f"Basic {expected}"
    assert req.headers["Content-Type"] == "application/x-www-form-urlencoded"
    # scope 명시 — 생략 시 빈 스코프·aud 없는 토큰이 발급돼 리소스 서버가 401 (8/26 실측)
    assert req.content == b"grant_type=client_credentials&scope=ops%3Aread+llm%3Ainvoke"


@pytest.mark.anyio
async def test_refreshes_before_expiry_margin(monkeypatch):
    servers = FakeServers(expires_in=900)
    auth = _auth()
    now = [1000.0]
    monkeypatch.setattr(oauth_client.time, "monotonic", lambda: now[0])

    await _call(auth, servers)
    now[0] += 900 - oauth_client.REFRESH_MARGIN_SECONDS - 1  # 여유 직전 — 아직 유효
    await _call(auth, servers)
    assert servers.issued == 1

    now[0] += 2  # 만료 60초 전 진입 — 호출 전에 선제 재발급
    await _call(auth, servers)
    assert servers.issued == 2
    assert servers.bearers[-1] == "Bearer tok-2"


@pytest.mark.anyio
async def test_401_triggers_reissue_and_single_retry():
    # 캐시상 유효한 tok-1 을 서버가 거부 (재기동으로 서명 키가 바뀐 경우 등) → 재발급 tok-2 로 1회 재시도
    servers = FakeServers(reject_tokens={"tok-1"})
    auth = _auth()

    response = await _call(auth, servers)

    assert response.status_code == 200
    assert servers.issued == 2
    assert servers.bearers == ["Bearer tok-1", "Bearer tok-2"]


@pytest.mark.anyio
async def test_401_after_retry_is_returned_as_is():
    servers = FakeServers(reject_tokens={"tok-1", "tok-2"})

    response = await _call(_auth(), servers)

    # 재시도는 1회뿐 — 무한 재발급 루프 없음
    assert response.status_code == 401
    assert servers.issued == 2
    assert len(servers.bearers) == 2


@pytest.mark.anyio
async def test_streaming_request_path_reads_token_body():
    # MCP 클라이언트의 실제 호출 형태 — client.stream() 경로에서도 토큰 응답 본문을 읽어 발급이 성립해야 한다
    servers = FakeServers(reject_tokens={"tok-1"})
    async with httpx.AsyncClient(transport=httpx.MockTransport(servers.handler), auth=_auth()) as client:
        async with client.stream("POST", RESOURCE_URL, json={}) as response:
            assert response.status_code == 200
            assert json.loads(await response.aread()) == {"ok": True}
    assert servers.issued == 2
    assert servers.bearers == ["Bearer tok-1", "Bearer tok-2"]


@pytest.mark.anyio
async def test_token_issue_failure_raises():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": "invalid_client"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler), auth=_auth()) as client:
        with pytest.raises(httpx.HTTPStatusError, match="invalid_client"):
            await client.post(RESOURCE_URL)


def test_sync_flow_issues_and_retries_once_on_401():
    """동기 경로(라우터 invoke) — 비동기와 같은 규칙: 최초 발급·캐시, 401 시 재발급 후 1회 재시도."""
    servers = FakeServers(reject_tokens={"tok-1"})
    auth = _auth()
    with httpx.Client(transport=httpx.MockTransport(servers.handler), auth=auth) as client:
        r1 = client.post(RESOURCE_URL, json={})
        r2 = client.post(RESOURCE_URL, json={})

    assert r1.status_code == 200 and r2.status_code == 200
    assert servers.issued == 2
    assert servers.bearers == ["Bearer tok-1", "Bearer tok-2", "Bearer tok-2"]


def test_shared_auth_is_one_object_per_registration():
    """MCP 연결과 게이트웨이 클라이언트가 같은 토큰 캐시를 쓴다 — 설정이 같으면 같은 객체."""
    from app.config.settings import Settings
    from app.tools.oauth_client import shared_auth

    s1 = Settings(_env_file=None, auth_client_secret="x")
    s2 = Settings(_env_file=None, auth_client_secret="x")
    other = Settings(_env_file=None, auth_client_secret="x", auth_scope="ops:read")

    assert shared_auth(s1) is shared_auth(s2)
    assert shared_auth(other) is not shared_auth(s1)
