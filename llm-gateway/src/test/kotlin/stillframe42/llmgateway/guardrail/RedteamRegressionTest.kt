package stillframe42.llmgateway.guardrail

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 레드팀 결정론 회귀 (2026-09-02) — docs/security/redteam/cases.yaml 을 데이터셋으로 직접 읽어
 * 게이트웨이가 맡는 계층(정규화 + 패턴 가드레일)을 실 LLM 없이 고정한다. 케이스 파일이 단일 원본 —
 * 케이스 추가·수정이 이 테스트에 자동 반영된다 (러너 E2E 와 동일 데이터).
 *
 * 판정 기준: 주입 문구는 패턴 단계에서 비클린(FLAGGED 또는 SUSPECT)이어야 한다 — SUSPECT 의 최종 판정(2차
 * 분류기)과 모델의 지시 이행 여부는 LLM 이 필요해 여기 없다 (수동 E2E 러너 몫).
 */
class RedteamRegressionTest {

    private val guardrail = PatternInputGuardrail()

    @Test
    fun `데이터셋 자체 방어 - 주입 케이스 축소는 회귀 커버리지 축소다`() {
        assertTrue(injectionCases.size >= 12, "주입 문구 케이스 ${injectionCases.size}건 — cases.yaml 경로·파싱 확인")
    }

    @TestFactory
    fun `주입 문구는 패턴 단계에서 비클린`(): List<DynamicTest> = injectionCases.map { case ->
        DynamicTest.dynamicTest("${case["id"]} (${case["category"]})") {
            val decision = guardrail.evaluate(case["injection"] as String)
            assertNotEquals(GuardrailVerdict.CLEAN, decision.verdict, "${case["id"]} 주입 문구가 패턴 단계를 통과했다")
        }
    }

    companion object {
        // gradle test 작업 디렉토리 = 모듈 루트 — 저장소 공용 데이터셋은 한 단계 위
        private val CASES_FILE: Path = Path.of("../docs/security/redteam/cases.yaml")

        private val injectionCases: List<Map<String, Any>> by lazy {
            val root = Files.newBufferedReader(CASES_FILE).use { Yaml().load<Map<String, Any>>(it) }
            @Suppress("UNCHECKED_CAST")
            val cases = root["cases"] as List<Map<String, Any>>
            cases.filter { it["injection"] is String }
        }
    }
}
