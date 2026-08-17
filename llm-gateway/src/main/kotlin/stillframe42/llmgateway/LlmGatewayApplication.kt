package stillframe42.llmgateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LlmGatewayApplication

fun main(args: Array<String>) {
	runApplication<LlmGatewayApplication>(*args)
}
