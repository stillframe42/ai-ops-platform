package stillframe42.llmgateway.guardrail

/** 판정을 내린 계층 — PATTERN(정규식·휴리스틱) / CLASSIFIER(LLM) / POLICY(차단 스위치) */
enum class GuardrailStage { NONE, PATTERN, CLASSIFIER, POLICY }
