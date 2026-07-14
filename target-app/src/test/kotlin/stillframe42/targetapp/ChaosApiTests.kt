package stillframe42.targetapp

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import stillframe42.targetapp.chaos.ChaosState

@SpringBootTest
@AutoConfigureMockMvc
class ChaosApiTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var chaosState: ChaosState

    @AfterEach
    fun resetChaos() {
        chaosState.reset()
    }

    @Test
    fun `상품 API 는 정상 상태에서 200 을 반환한다`() {
        mockMvc.perform(get("/products"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(1))
    }

    @Test
    fun `존재하지 않는 상품은 404 를 반환한다`() {
        mockMvc.perform(get("/products/999"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `에러율 100퍼센트 주입 시 상품 API 가 500 을 반환한다`() {
        mockMvc.perform(post("/chaos/error-rate").param("percent", "100"))
            .andExpect(status().isOk)

        mockMvc.perform(get("/products"))
            .andExpect(status().isInternalServerError)
    }

    @Test
    fun `에러율 주입 중에도 chaos 관리 API 는 영향받지 않는다`() {
        mockMvc.perform(post("/chaos/error-rate").param("percent", "100"))
            .andExpect(status().isOk)

        mockMvc.perform(get("/chaos"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errorRate.percent").value(100))
    }

    @Test
    fun `reset 하면 주입이 해제되고 상품 API 가 회복된다`() {
        mockMvc.perform(post("/chaos/error-rate").param("percent", "100"))
        mockMvc.perform(post("/chaos/reset"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errorRate").isEmpty)

        mockMvc.perform(get("/products"))
            .andExpect(status().isOk)
    }

    @Test
    fun `잘못된 percent 는 400 을 반환한다`() {
        mockMvc.perform(post("/chaos/latency").param("ms", "1000").param("percent", "150"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `latency 주입 상태가 조회에 노출된다`() {
        mockMvc.perform(post("/chaos/latency").param("ms", "100"))
            .andExpect(status().isOk)

        mockMvc.perform(get("/chaos"))
            .andExpect(jsonPath("$.latency.delayMs").value(100))
            .andExpect(jsonPath("$.latency.percent").value(100))
    }
}
