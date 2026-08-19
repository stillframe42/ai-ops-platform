package stillframe42.llmgateway

import java.time.Duration
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class LlmGatewayApplicationTests {

	@Test
	fun contextLoads() {
	}

	// 주입 RestClient.Builder 의 공통 타임아웃 원천 — yml(spring.http.clients.*) 바인딩 검증
	// (8/19: 사용처별 requestFactory 조립 제거의 전제가 이 중앙 설정)
	@Autowired
	lateinit var httpClientSettings: HttpClientSettings

	@Test
	fun `HTTP 클라이언트 공통 타임아웃이 yml 에서 바인딩된다`() {
		assertEquals(Duration.ofSeconds(3), httpClientSettings.connectTimeout())
		assertEquals(Duration.ofSeconds(5), httpClientSettings.readTimeout())
	}
}
