"""Client Credentials 토큰 처리 (ADR-0016) — httpx.Auth 구현으로 MCP 연결에 주입한다.

토큰은 프로세스 안에 캐시하고 만료 60초 전에 재발급한다. Client Credentials 에는 refresh token 이
없으므로(RFC 6749 §4.4.3) 갱신 = 재발급이다. 승인 대기로 수 시간 중단된 그래프가 재개될 때
만료 토큰으로 호출되는 경우는 401 수신 → 재발급 → 1회 재시도로 흡수한다 ("호출 시점 유효" 보장).

토큰 요청 자체도 auth flow 안에서 yield 한다 — 별도 클라이언트 없이 호출 중인 httpx 클라이언트를
그대로 쓰는 httpx 의 표준 패턴. 동시 호출이 겹쳐 재발급이 중복돼도 결과는 같은 유효 토큰이라 잠금은 두지 않는다.
"""

import base64
import logging
import time
from urllib.parse import urlencode
from collections.abc import AsyncGenerator

import httpx

logger = logging.getLogger(__name__)

# 만료 임박 판정 여유 — 이 안에 들면 호출 전에 미리 재발급
REFRESH_MARGIN_SECONDS = 60


class ClientCredentialsAuth(httpx.Auth):
    # requires_response_body 는 쓰지 않는다 — MCP 클라이언트는 client.stream() 으로 호출하며 그 경로에서는
    # 선적재가 적용되지 않아 ResponseNotRead 가 난다 (8/26 클러스터 실측). 토큰 응답만 flow 안에서 직접 읽는다

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

    async def _issue(self, response: httpx.Response) -> None:
        await response.aread()
        if response.status_code != 200:
            # 본문에 시크릿이 실리지 않는다 (error·error_description 만) — 로그 노출 안전
            raise httpx.HTTPStatusError(
                f"토큰 발급 실패 {response.status_code}: {response.text}",
                request=response.request,
                response=response,
            )
        payload = response.json()
        self._access_token = payload["access_token"]
        self._expires_at = time.monotonic() + float(payload.get("expires_in", 0))
        logger.info("액세스 토큰 발급 — client_id=%s expires_in=%ss", self._client_id, payload.get("expires_in"))

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

    def sync_auth_flow(self, request: httpx.Request):
        # MCP 클라이언트는 비동기 전용 — 동기 경로는 지원하지 않는다
        raise NotImplementedError("ClientCredentialsAuth 는 async 전용")
