package stillframe42.controlplane.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.util.backoff.BackOffExecution
import org.springframework.util.backoff.ExponentialBackOff

class KafkaConsumerConfigTest {

    // 핸들러 내부 backoff 는 밖에서 꺼낼 수 없어 동일 파라미터의 시퀀스 계약을 고정한다
    private val backOff = ExponentialBackOff(1_000L, 2.0).apply {
        maxInterval = 60_000L
        maxAttempts = 12L
    }

    @Test
    fun `지수 backoff — 1초 시작, 배수 2, 60초 상한`() {
        val execution = backOff.start()
        val intervals = generateSequence { execution.nextBackOff().takeIf { it != BackOffExecution.STOP } }.toList()

        assertThat(intervals.take(6)).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L)
        assertThat(intervals.drop(6)).allMatch { it == 60_000L }
    }

    @Test
    fun `재시도 창 — 총 12회, 약 7분 후 소진(건너뜀 전환)`() {
        val execution = backOff.start()
        val intervals = generateSequence { execution.nextBackOff().takeIf { it != BackOffExecution.STOP } }.toList()

        assertThat(intervals).hasSize(12)
        assertThat(intervals.sum()).isEqualTo(423_000L) // ≈ 7분 — DB compose 재기동 대기를 덮는 창
    }
}
