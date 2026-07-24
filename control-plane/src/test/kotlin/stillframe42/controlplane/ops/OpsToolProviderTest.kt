package stillframe42.controlplane.ops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.Filter
import tools.jackson.databind.json.JsonMapper

/** 단위 테스트 경계 — 실 DB·임베딩 API 무의존 (agent-service 관례와 동일). VectorStore 는 스텁. */
class OpsToolProviderTest {

    private class StubVectorStore(private val results: List<Document>) : VectorStore {
        var lastRequest: SearchRequest? = null

        override fun add(documents: List<Document>) = Unit
        override fun delete(idList: List<String>) = Unit
        override fun delete(filterExpression: Filter.Expression) = Unit
        override fun similaritySearch(request: SearchRequest): List<Document> {
            lastRequest = request
            return results
        }
    }

    private val mapper = JsonMapper.builder().build()
    private val registry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()

    private fun provider(results: List<Document> = emptyList()) =
        OpsToolProvider(StubVectorStore(results), McpToolMetrics(registry))

    // --- 계측 배선 (DAY 17) — 명시적 계측이라 도구마다 record 호출 누락이 가능, 여기서 강제한다 ---

    @Test
    fun `도구 3종 모두 호출 시 mcp tool calls 타이머가 기록된다`() {
        val p = provider()
        p.getDeploymentHistory("target-app")
        p.searchSimilarIncidents("p95 지연 급등")
        p.getAppConfig("unknown-app") // error 필드 응답 — degraded 로 기록되는 경로

        for (tool in listOf("getDeploymentHistory", "searchSimilarIncidents", "getAppConfig")) {
            assertTrue(
                registry.find("mcp.tool.calls").tag("tool", tool).timers().isNotEmpty(),
                "$tool 계측 누락 — McpToolMetrics.record 로 감싸야 한다",
            )
        }
        assertTrue(registry.find("mcp.tool.calls").tag("outcome", "degraded").timers().isNotEmpty())
    }

    // --- getDeploymentHistory ---

    @Test
    fun `배포 이력 - 아는 앱이면 시드 배포 목록을 JSON 배열로 반환한다`() {
        val json = mapper.readTree(provider().getDeploymentHistory("target-app"))

        assertTrue(json.isArray)
        assertTrue(json.size() >= 1)
        assertEquals("target-app", json[0]["service"].asString())
        assertNotNull(json[0]["deployed_at"], "배포 시각 필드가 있어야 분석 에이전트가 상관관계를 판단한다")
        assertNotNull(json[0]["change_summary"])
    }

    @Test
    fun `배포 이력 - 모르는 앱이면 빈 배열을 반환한다`() {
        val json = mapper.readTree(provider().getDeploymentHistory("unknown-app"))

        assertTrue(json.isArray)
        assertEquals(0, json.size())
    }

    // --- searchSimilarIncidents ---

    @Test
    fun `유사 인시던트 - 벡터 스토어에 topK 3 으로 질의하고 본문과 메타데이터를 반환한다`() {
        val doc = Document(
            "11111111-1111-1111-1111-111111111111",
            "5xx 오류율 급증 — ChaosInterceptor 로 인한 의도적 오류 주입",
            mapOf("incident_id" to "inc-001", "severity" to "P2", "root_cause" to "오류 주입"),
        )
        val store = StubVectorStore(listOf(doc))
        val toolProvider = OpsToolProvider(store, McpToolMetrics(registry))

        val json = mapper.readTree(toolProvider.searchSimilarIncidents("5xx 오류율이 급증하고 있다"))

        assertEquals("5xx 오류율이 급증하고 있다", store.lastRequest?.query)
        assertEquals(3, store.lastRequest?.topK)
        assertTrue(json.isArray)
        assertEquals("inc-001", json[0]["incident_id"].asString())
        assertEquals("P2", json[0]["severity"].asString())
        assertTrue(json[0]["summary"].asString().contains("5xx"))
    }

    @Test
    fun `유사 인시던트 - 벡터 스토어 실패 시 error 필드 JSON 으로 응답한다 - 도구 호출이 예외로 죽지 않는다`() {
        val failing = object : VectorStore {
            override fun add(documents: List<Document>) = Unit
            override fun delete(idList: List<String>) = Unit
            override fun delete(filterExpression: Filter.Expression) = Unit
            override fun similaritySearch(request: SearchRequest): List<Document> =
                throw IllegalStateException("임베딩 API 인증 실패")
        }

        val json = mapper.readTree(OpsToolProvider(failing, McpToolMetrics(registry)).searchSimilarIncidents("5xx 급증"))

        assertNotNull(json["error"], "실패는 예외 전파 대신 error 필드로 — 에이전트가 부분 진행할 수 있게 (DAY 13 관례)")
    }

    @Test
    fun `유사 인시던트 - 검색 결과가 없으면 빈 배열을 반환한다`() {
        val json = mapper.readTree(provider().searchSimilarIncidents("전례 없는 증상"))

        assertTrue(json.isArray)
        assertEquals(0, json.size())
    }

    // --- getAppConfig ---

    @Test
    fun `앱 설정 - 아는 앱이면 포트와 헬스체크 경로를 포함한 설정을 반환한다`() {
        val json = mapper.readTree(provider().getAppConfig("target-app"))

        assertEquals("target-app", json["app"].asString())
        assertEquals(8080, json["port"].asInt())
        assertNotNull(json["health_endpoint"])
    }

    @Test
    fun `앱 설정 - 모르는 앱이면 error 필드로 알린다`() {
        val json = mapper.readTree(provider().getAppConfig("unknown-app"))

        assertNotNull(json["error"], "없는 앱은 조용히 빈 값 대신 명시적 오류를 알린다")
    }

    // --- 도구 메타데이터 ---

    @Test
    fun `도구 3종 전부 읽기 전용으로 광고된다 - 권한 분류의 기초 메타데이터`() {
        // 기본값은 destructiveHint=true — 조회 도구가 파괴적 도구로 광고되면
        // 클라이언트(에이전트)의 안전 판단을 오염시킨다 (DAY 17 도구 카탈로그 권한 분류의 전제)
        listOf("getDeploymentHistory", "searchSimilarIncidents", "getAppConfig").forEach { name ->
            val method = OpsToolProvider::class.java.declaredMethods.single { it.name == name }
            val annotations = method.getAnnotation(org.springframework.ai.mcp.annotation.McpTool::class.java).annotations
            assertTrue(annotations.readOnlyHint, "$name 은 readOnlyHint=true 여야 한다")
            assertTrue(!annotations.destructiveHint, "$name 은 destructiveHint=false 여야 한다")
        }
    }
}
