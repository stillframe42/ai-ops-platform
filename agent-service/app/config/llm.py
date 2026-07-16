from langchain.chat_models import init_chat_model
from langchain_core.language_models.chat_models import BaseChatModel

from app.config.settings import Settings


def create_llm(settings: Settings) -> BaseChatModel:
    """설정의 "프로바이더:모델" 문자열로 LLM 을 만든다.

    에이전트 코드는 프로바이더를 모른다 — 전환은 LLM_MODEL 변경만으로 끝난다 (ADR-0007).
    """
    return init_chat_model(settings.llm_model, api_key=settings.active_llm_api_key())
