# 실험 히스토리

프롬프트·모델 변경을 데이터로 검증한 A/B 실험 기록 (ADR-0019 결정 ③ — 2단 배정·사전 승자 기준). 실험 1건 = 파일 1개, 판정 근거는 사전 기준과 대조해 적는다.
실험 정의는 `agent-service/app/experiments/experiments.yml`, 집계·판정은 `evaluation-service/scripts/experiment_report.py`, 절차는 `docs/quality-evaluation.md` §5.

| 날짜 | 실험 | 변인 | 판정 | 파일 |
|---|---|---|---|---|
| 2026-09-16 | `analysis-prompt-v2` | 분석 프롬프트 v1 → v2 (추론 구조) | 보류 — F +0.03 (CI −0.14~+0.20), A 11 · B 4 | [2026-09-16-analysis-prompt-v2.md](2026-09-16-analysis-prompt-v2.md) |
| 2026-09-16 | `analysis-model-haiku` (재생) | 분석 모델 claude-sonnet-5 → claude-haiku-4-5 | 보류 — F −0.08 (CI −0.25~+0.08), 비용 −62%, 저품질률 55% vs 18% | 위 파일 §7 |
