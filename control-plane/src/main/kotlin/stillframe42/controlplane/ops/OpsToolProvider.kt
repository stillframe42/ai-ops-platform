package stillframe42.controlplane.ops

import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * 운영 도구 3종을 MCP 도구로 노출한다 (DAY 15, ADR-0010).
 *
 * agent-service 의 로컬 더미 도구(get_recent_deployments)를 서버 측으로 이관하는 그림 —
 * 도구 스키마·데이터가 control-plane 한 곳에서 관리되고, Python 쪽은 프로토콜로 발견만 한다.
 * 반환은 전부 JSON 문자열 — LLM 도구 결과 관례 (agent-service 의 json.dumps 와 동일).
 */
@Component
class OpsToolProvider(private val vectorStore: VectorStore) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()

    @McpTool(
        name = "getDeploymentHistory",
        description = "앱의 최근 배포 이력을 조회한다 — 장애 시점과 배포의 상관을 확인/배제하는 용도",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun getDeploymentHistory(
        // 닫힌 도메인은 "예:" 대신 지원 목록 명시 — LLM 의 인자 변형("타겟앱" 등) 방지
        @McpToolParam(description = "조회 대상 앱 이름 (현재 지원: target-app)", required = true) app: String,
    ): String = mapper.writeValueAsString(DEPLOYMENTS[app] ?: emptyList<Any>())

    @McpTool(
        name = "searchSimilarIncidents",
        description = "증상 설명과 유사한 과거 인시던트를 벡터 검색으로 조회한다 — 과거 원인·조치를 현재 분석의 참고로",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun searchSimilarIncidents(
        @McpToolParam(
            description = "현재 관찰 중인 증상을 서술한 자연어 문장 (예: \"주문 API 의 p95 지연이 4초까지 급등했다\") — 문장이 구체적일수록 유사도 검색 품질이 좋아진다",
            required = true,
        ) symptom: String,
    ): String {
        // 검색 실패(임베딩 키 미설정·DB 다운 등)는 예외 전파 대신 error 필드로 —
        // 에이전트가 이 도구 없이 부분 진행할 수 있게 한다 (DAY 13 복원력 관례)
        val results = try {
            vectorStore.similaritySearch(
                SearchRequest.builder().query(symptom).topK(SIMILAR_TOP_K).build(),
            )
        } catch (e: Exception) {
            logger.warn("유사 인시던트 검색 실패 — {}", e.message)
            return mapper.writeValueAsString(mapOf("error" to "유사 인시던트 검색 불가: ${e.message}"))
        }
        val payload = results.orEmpty().map { doc ->
            doc.metadata + mapOf("summary" to doc.text, "score" to doc.score)
        }
        return mapper.writeValueAsString(payload)
    }

    @McpTool(
        name = "getAppConfig",
        description = "앱의 배포·런타임 설정 정보를 조회한다 — 리소스 한계, 엔드포인트 등 조치 판단의 근거",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun getAppConfig(
        @McpToolParam(description = "조회 대상 앱 이름 (현재 지원: target-app)", required = true) app: String,
    ): String {
        val config = APP_CONFIGS[app]
            ?: return mapper.writeValueAsString(mapOf("error" to "알 수 없는 앱: $app (사용 가능: ${APP_CONFIGS.keys})"))
        return mapper.writeValueAsString(config)
    }

    companion object {
        private const val SIMILAR_TOP_K = 3

        // 실제 배포 이력 시스템 연동 전까지의 시드 — target-app 최초 기동 시점 기준
        // (agent-service deployment_tools.py 의 더미와 동일 값 유지)
        private val DEPLOYMENTS = mapOf(
            "target-app" to listOf(
                mapOf(
                    "service" to "target-app",
                    "version" to "0.0.1-SNAPSHOT",
                    "deployed_at" to "2026-07-14T10:00:00+09:00",
                    "change_summary" to "초기 배포 (데모 앱)",
                ),
            ),
        )

        private val APP_CONFIGS = mapOf(
            "target-app" to mapOf(
                "app" to "target-app",
                "port" to 8080,
                "replicas" to 1,
                "health_endpoint" to "/actuator/health",
                "metrics_endpoint" to "/actuator/prometheus",
                "runtime" to "Spring Boot (docker compose 단일 컨테이너)",
                "notes" to "fault-injection(chaos) 엔드포인트를 제공하는 데모 앱 — 재기동으로 chaos 상태가 초기화된다",
            ),
        )
    }
}
