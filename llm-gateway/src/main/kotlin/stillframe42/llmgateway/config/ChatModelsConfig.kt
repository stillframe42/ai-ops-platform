package stillframe42.llmgateway.config

import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import stillframe42.llmgateway.routing.Provider

/**
 * 멀티 프로바이더 (weekly-plan Phase 2) — spring.ai.model.chat 미지정으로 양쪽 자동구성을 살리고,
 * 라우팅이 참조할 프로바이더 → ChatModel 사전을 만든다
 */
@Configuration
class ChatModelsConfig {

    @Bean
    fun chatModels(anthropic: AnthropicChatModel, openai: OpenAiChatModel): Map<Provider, ChatModel> =
        mapOf(Provider.ANTHROPIC to anthropic, Provider.OPENAI to openai)
}
