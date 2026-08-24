package stillframe42.llmgateway.relay

import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.stereotype.Service
import stillframe42.llmgateway.api.EmbeddingData
import stillframe42.llmgateway.api.EmbeddingUsage
import stillframe42.llmgateway.api.EmbeddingsRequest
import stillframe42.llmgateway.api.EmbeddingsResponse

/**
 * OpenAI 형식 임베딩 요청을 EmbeddingModel(OpenAI — yml 선택)로 중계한다.
 * 경유 결정 근거: control-plane 의 유일한 LLM 호출이 임베딩
 */
@Service
class EmbeddingRelayService(
    private val embeddingModel: EmbeddingModel,
) {

    fun relay(request: EmbeddingsRequest): EmbeddingsResponse {
        val response = embeddingModel.call(EmbeddingRequest(request.inputTexts(), null))
        val usage = response.metadata.usage
        return EmbeddingsResponse(
            data = response.results.mapIndexed { i, embedding ->
                EmbeddingData(index = i, embedding = embedding.output.toList())
            },
            model = response.metadata.model.takeIf { it.isNotBlank() } ?: request.model.orEmpty(),
            usage = EmbeddingUsage(
                promptTokens = usage.promptTokens,
                totalTokens = usage.totalTokens,
            ),
        )
    }
}
