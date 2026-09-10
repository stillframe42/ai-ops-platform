"""Client Credentials 토큰 처리 (ADR-0016) — httpx.Auth 구현 (agent-service `tools/oauth_client.py` 이식).

토큰은 프로세스 안에 캐시하고 만료 60초 전에 재발급한다. 401 수신 → 재발급 → 1회 재시도로 "호출 시점 유효" 를 보장한다.
토큰 요청 자체도 auth flow 안에서 yield 한다 — 호출 중인 httpx 클라이언트를 그대로 쓰는 httpx 의 표준 패턴.
"""

import base64
import logging
import time
from collections.abc import AsyncGenerator, Generator
from urllib.parse import urlencode

import httpx

from evaluation.config.settings import Settings

logger = logging.getLogger(__name__)

# 만료 임박 판정 여유 — 이 안에 들면 호출 전에 미리 재발급
REFRESH_MARGIN_SECONDS = 60


class ClientCredentialsAuth(httpx.Auth):
    def __init__(self, token_url: str, client_id: str, client_secret: str, scope: str) -> None:
        self._token_url = token_url
        self._client_id = client_id
        self._client_secret = client_secret
        self._scope = scope
        self._access_token: str | None = None
        self._expires_at: float = 0.0

    def _is_expiring(self, now: float) -> bool:
        return self._access_token is None or now >= self._expires_at - REFRESH_MARGIN_SECONDS

    def _token_request(self) -> httpx.Request:
        # client_secret_basic — auth-server 클라이언트 등록의 인증 방식과 계약
        credentials = base64.b64encode(f"{self._client_id}:{self._client_secret}".encode()).decode()
        return httpx.Request(
            "POST",
            self._token_url,
            headers={
                "Authorization": f"Basic {credentials}",
                "Content-Type": "application/x-www-form-urlencoded",
            },
            # scope 를 생략하면 빈 스코프 토큰(aud 없음)이 발급된다 — 반드시 명시
            content=urlencode({"grant_type": "client_credentials", "scope": self._scope}).encode(),
        )

    def _store(self, payload: dict) -> None:
        self._access_token = payload["access_token"]
        self._expires_at = time.monotonic() + float(payload.get("expires_in", 0))
        logger.info("액세스 토큰 발급 — client_id=%s expires_in=%ss", self._client_id, payload.get("expires_in"))

    def _raise_if_failed(self, response: httpx.Response) -> None:
        if response.status_code != 200:
            # 본문에 시크릿이 실리지 않는다 (error·error_description 만) — 로그 노출 안전
            raise httpx.HTTPStatusError(
                f"토큰 발급 실패 {response.status_code}: {response.text}",
                request=response.request,
                response=response,
            )

    async def _issue(self, response: httpx.Response) -> None:
        await response.aread()
        self._raise_if_failed(response)
        self._store(response.json())

    async def async_auth_flow(self, request: httpx.Request) -> AsyncGenerator[httpx.Request, httpx.Response]:
        if self._is_expiring(time.monotonic()):
            await self._issue((yield self._token_request()))
        request.headers["Authorization"] = f"Bearer {self._access_token}"
        response = yield request
        if response.status_code == 401:
            # 캐시 기준으로는 유효했으나 서버가 거부 — 재발급 후 1회만 재시도 (그래도 401 이면 그대로 반환)
            self._access_token = None
            await self._issue((yield self._token_request()))
            request.headers["Authorization"] = f"Bearer {self._access_token}"
            yield request

    def _issue_sync(self, response: httpx.Response) -> None:
        response.read()
        self._raise_if_failed(response)
        self._store(response.json())

    def sync_auth_flow(self, request: httpx.Request) -> Generator[httpx.Request, httpx.Response, None]:
        if self._is_expiring(time.monotonic()):
            self._issue_sync((yield self._token_request()))
        request.headers["Authorization"] = f"Bearer {self._access_token}"
        response = yield request
        if response.status_code == 401:
            self._access_token = None
            self._issue_sync((yield self._token_request()))
            request.headers["Authorization"] = f"Bearer {self._access_token}"
            yield request


_shared: dict[tuple[str, str, str], ClientCredentialsAuth] = {}


def shared_auth(settings: Settings) -> ClientCredentialsAuth:
    """프로세스 공유 Auth — 같은 (token url·client id·scope) 조합이면 같은 객체(토큰 캐시)를 돌려준다."""
    key = (settings.auth_token_url, settings.auth_client_id, settings.auth_scope)
    if key not in _shared:
        _shared[key] = ClientCredentialsAuth(
            settings.auth_token_url, settings.auth_client_id, settings.auth_client_secret, settings.auth_scope
        )
    return _shared[key]
