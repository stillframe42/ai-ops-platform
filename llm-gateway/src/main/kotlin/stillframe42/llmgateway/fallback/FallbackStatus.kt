package stillframe42.llmgateway.fallback

/** 폴백 판정 — NONE = 주 중계 성공, PROVIDER = 교차 프로바이더 재중계, LOCAL = 로컬 폴백 응답 */
enum class FallbackStatus { NONE, PROVIDER, LOCAL }
