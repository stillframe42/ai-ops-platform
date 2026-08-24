"""Langfuse 트레이싱 — 키-게이트 방식 (키 없으면 완전 비활성, DAY 14).

인스턴스는 직전 프로젝트 스택의 Langfuse v3 재활용 (2026-07-21 v2→v3
업그레이드). 세션 연결 규약: langfuse_session_id = thread_id = incident id — 인시던트
1건의 전체 LLM 호출·비용이 Langfuse 세션 하나로 묶인다.
"""

import logging

from app.config.settings import Settings

logger = logging.getLogger(__name__)


def build_langfuse_handler(settings: Settings):
    """설정에 키 3종이 모두 있으면 LangChain 콜백 핸들러를, 아니면 None 을 반환한다.

    None 이면 트레이싱 완전 비활성 — 실행 config 에 콜백 자체가 들어가지 않는다.
    import 를 함수 안에서 하는 이유: 비활성 환경(단위 테스트 등)에서 langfuse 클라이언트의
    백그라운드 스레드가 생성되지 않게 하기 위해서다.
    """
    if not (
        settings.langfuse_host
        and settings.langfuse_public_key
        and settings.langfuse_secret_key
    ):
        logger.info("Langfuse 비활성 — LANGFUSE_HOST/PUBLIC_KEY/SECRET_KEY 미설정")
        return None

    from langfuse import Langfuse
    from langfuse.langchain import CallbackHandler

    # 클라이언트 싱글턴을 명시 초기화 — SDK 는 os.environ 을 읽지만 우리 설정은
    # pydantic-settings(.env 파일) 경유라 환경 변수가 없을 수 있다
    Langfuse(
        public_key=settings.langfuse_public_key,
        secret_key=settings.langfuse_secret_key,
        host=settings.langfuse_host,
    )
    logger.info("Langfuse 활성 — host=%s", settings.langfuse_host)
    return CallbackHandler(public_key=settings.langfuse_public_key)
