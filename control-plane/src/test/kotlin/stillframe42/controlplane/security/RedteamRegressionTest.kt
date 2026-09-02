package stillframe42.controlplane.security

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.ai.document.Document
import org.yaml.snakeyaml.Yaml
import stillframe42.controlplane.ops.IncidentSeedLoader
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 레드팀 결정론 회귀 (2026-09-02) — docs/security/redteam/cases.yaml 을 데이터셋으로 직접 읽어
 * control-plane 이 맡는 계층(저장 시점 스캔 — RT-10·11)을 실 LLM 없이 고정한다. 케이스 파일이 단일 원본 —
 * 케이스 추가·수정이 이 테스트에 자동 반영된다 (러너 E2E 와 동일 데이터).
 *
 * 저장 스캔은 확정 패턴만 쓴다 (오탐 = 데이터 소실) — 확정 패턴 밖 주입(RT-07 류 사회공학 문구)은
 * agent-service 구조적 분리·게이트웨이 가드레일 몫이라 여기서 검사하지 않는다.
 */
class RedteamRegressionTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `확정 패턴 커버리지 - 저장 경로 케이스 RT-06·08 은 스캐너가 탐지해야 한다`() {
        val detected = injectionCases.filter { PromptInjectionScanner.detect(it["injection"] as String) != null }
            .map { it["id"] as String }
        assertTrue("RT-06" in detected, "RT-06 URI 주입이 확정 패턴에서 빠졌다 (탐지: $detected)")
        assertTrue("RT-08" in detected, "RT-08 annotation 주입이 확정 패턴에서 빠졌다 (탐지: $detected)")
    }

    @TestFactory
    fun `확정 패턴 주입은 저장 보고서에서 값 전체 대체된다 - RT-10 판정 기준`(): List<DynamicTest> =
        injectionCases.filter { PromptInjectionScanner.detect(it["injection"] as String) != null }.map { case ->
            DynamicTest.dynamicTest("${case["id"]} (${case["category"]})") {
                val injection = case["injection"] as String
                val payload = mapper.writeValueAsString(
                    mapOf(
                        "incident_id" to "inc-redteam",
                        "analysis" to mapOf("root_cause_hypothesis" to "가설", "evidence" to listOf(injection)),
                    ),
                )
                val result = StoredReportSanitizer.sanitize(payload)
                assertTrue(result.findings.any { it.endsWith(":injection") }, "${case["id"]} 저장 스캔 미탐지")
                (case["marker"] as? String)?.let { marker ->
                    assertFalse(marker in result.payload, "${case["id"]} 마커 $marker 가 저장본에 잔존")
                }
            }
        }

    @TestFactory
    fun `확정 패턴 주입 시드는 적재에서 제외된다 - RT-11 판정 기준`(): List<DynamicTest> =
        injectionCases.filter { PromptInjectionScanner.detect(it["injection"] as String) != null }.map { case ->
            DynamicTest.dynamicTest("${case["id"]} (${case["category"]})") {
                val seed = Document("seed-${case["id"]}", case["injection"] as String, mapOf("scenario" to "redteam"))
                assertTrue(IncidentSeedLoader.accepted(listOf(seed)).isEmpty(), "${case["id"]} 주입 시드가 적재됐다")
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
