package stillframe42.controlplane.ops

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * searchSimilarIncidents 의 조회 대상 시드 — 실 인시던트 보고서 축적(DAY 20 결과 컨슈머) 전까지의
 * 과거 사례 역할. 시나리오 3계열(오류율·지연·메모리)과 대응되는 가상 운영 이력이다.
 *
 * 멱등성: id 가 시드 키의 UUID v3 (nameUUIDFromBytes) 라 기동마다 add 해도 pgvector 가
 * ON CONFLICT id DO UPDATE 로 upsert — 행 증식 없이 본문 수정만 반영(재임베딩)된다.
 */
@Component
class IncidentSeedLoader(private val vectorStore: VectorStore) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        // 시드 실패(임베딩 키 미설정 등)가 앱 기동을 막으면 안 된다 — 검색 외 도구 2종은
        // 벡터 스토어 무의존이라 여전히 제공 가능 (Langfuse 키-게이트와 같은 조용한 비활성 관례)
        try {
            vectorStore.add(SEED_INCIDENTS)
            logger.info("시드 인시던트 {}건 적재 완료 (upsert)", SEED_INCIDENTS.size)
        } catch (e: Exception) {
            logger.warn("시드 인시던트 적재 실패 — searchSimilarIncidents 는 빈 결과/오류로 동작: {}", e.message)
        }
    }

    companion object {
        private fun seed(key: String, summary: String, metadata: Map<String, Any>) = Document(
            UUID.nameUUIDFromBytes("aiops-seed-$key".toByteArray()).toString(),
            summary,
            metadata,
        )

        val SEED_INCIDENTS: List<Document> = listOf(
            seed(
                "inc-2026-0412",
                "결제 API 5xx 오류율이 배포 직후 40%까지 급증. 신규 배포 코드의 널 참조 예외가 원인으로, " +
                    "직전 버전 롤백 후 5분 내 정상화. 오류율 급증이 배포 시각과 겹치면 배포 원인을 최우선 의심.",
                mapOf(
                    "incident_id" to "inc-2026-0412",
                    "severity" to "P1",
                    "root_cause" to "신규 배포 코드의 NullPointerException — 결제 요청 검증 누락",
                    "action_taken" to "ROLLBACK (직전 버전) + 핫픽스 배포",
                    "occurred_at" to "2026-04-12T14:20:00+09:00",
                ),
            ),
            seed(
                "inc-2026-0503",
                "주문 조회 p95 지연이 300ms 에서 4초로 급등. DB 커넥션 풀 고갈이 원인 — 슬로우 쿼리가 " +
                    "커넥션을 장시간 점유해 대기 큐가 누적. 풀 확장은 임시 조치였고 근본 해소는 쿼리 인덱스 추가.",
                mapOf(
                    "incident_id" to "inc-2026-0503",
                    "severity" to "P2",
                    "root_cause" to "DB 커넥션 풀 고갈 — 인덱스 없는 슬로우 쿼리의 커넥션 점유",
                    "action_taken" to "커넥션 풀 확장 (임시) + 인덱스 추가 (근본)",
                    "occurred_at" to "2026-05-03T10:45:00+09:00",
                ),
            ),
            seed(
                "inc-2026-0619",
                "정산 서비스 힙 사용량이 30분에 걸쳐 우상향 추세로 증가하다 OOM 직전 도달. TTL 없는 " +
                    "로컬 캐시가 무한 증식한 메모리 누수. 재기동으로 즉시 완화 후 캐시 TTL·상한 설정으로 재발 방지.",
                mapOf(
                    "incident_id" to "inc-2026-0619",
                    "severity" to "P2",
                    "root_cause" to "메모리 누수 — TTL 미설정 로컬 캐시의 무한 증식",
                    "action_taken" to "RESTART_APP (완화) + 캐시 TTL·최대 크기 설정 (재발 방지)",
                    "occurred_at" to "2026-06-19T16:05:00+09:00",
                ),
            ),
        )
    }
}
