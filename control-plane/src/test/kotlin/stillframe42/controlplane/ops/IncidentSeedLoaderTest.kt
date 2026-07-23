package stillframe42.controlplane.ops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.boot.DefaultApplicationArguments

/** 단위 테스트 경계 — 실 DB·임베딩 API 무의존. add 호출을 기록하는 스텁으로 검증. */
class IncidentSeedLoaderTest {

    private class RecordingVectorStore : VectorStore {
        val added = mutableListOf<List<Document>>()

        override fun add(documents: List<Document>) {
            added += documents
        }

        override fun delete(idList: List<String>) = Unit
        override fun delete(filterExpression: Filter.Expression) = Unit
        override fun similaritySearch(request: SearchRequest): List<Document> = emptyList()
    }

    @Test
    fun `기동 시 시드 인시던트를 벡터 스토어에 적재한다 - 본문과 필수 메타데이터 포함`() {
        val store = RecordingVectorStore()

        IncidentSeedLoader(store).run(DefaultApplicationArguments())

        assertEquals(1, store.added.size, "add 는 한 번에 배치로 호출한다")
        val docs = store.added.single()
        assertTrue(docs.size >= 3, "시나리오 3계열(오류율·지연·메모리)을 커버할 최소 3건")
        docs.forEach { doc ->
            assertTrue(doc.text!!.isNotBlank())
            listOf("incident_id", "severity", "root_cause", "action_taken", "occurred_at").forEach { key ->
                assertTrue(key in doc.metadata, "메타데이터 $key 는 분석 에이전트의 참고 근거라 필수")
            }
        }
    }

    @Test
    fun `벡터 스토어 실패 시 예외를 삼키고 앱 기동을 막지 않는다 - 나머지 도구 2종은 여전히 동작해야 한다`() {
        val failing = object : VectorStore {
            override fun add(documents: List<Document>) =
                throw IllegalStateException("임베딩 키 미설정")

            override fun delete(idList: List<String>) = Unit
            override fun delete(filterExpression: Filter.Expression) = Unit
            override fun similaritySearch(request: SearchRequest): List<Document> = emptyList()
        }

        // 예외가 전파되면 ApplicationRunner 가 기동 자체를 실패시킨다 — 던지지 않아야 통과
        IncidentSeedLoader(failing).run(DefaultApplicationArguments())
    }

    @Test
    fun `시드 문서 id 는 결정적이다 - 재기동해도 같은 id 로 upsert 되어 중복이 없다`() {
        val first = RecordingVectorStore()
        val second = RecordingVectorStore()

        IncidentSeedLoader(first).run(DefaultApplicationArguments())
        IncidentSeedLoader(second).run(DefaultApplicationArguments())

        val firstIds = first.added.single().map { it.id }
        val secondIds = second.added.single().map { it.id }
        assertEquals(firstIds, secondIds)
        assertEquals(firstIds.size, firstIds.toSet().size, "시드 간 id 충돌이 없어야 한다")
    }
}
