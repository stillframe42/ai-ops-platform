package stillframe42.targetapp.chaos

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * heap 을 점진적으로 점유해 메모리 누수 패턴(GC 후에도 우상향)을 시뮬레이션한다.
 * 단발 스파이크가 아닌 "분당 증가량" 의미론 — 시나리오 3 의 추세 트리거가 이 성질을 요구한다.
 */
@Component
class MemoryLeakSimulator(private val chaosState: ChaosState) {

    private val retained = mutableListOf<ByteArray>()

    @Scheduled(fixedRate = TICK_MS)
    fun tick() {
        val fault = chaosState.memoryLeak
        if (fault == null) {
            release()
            return
        }
        val bytesPerTick = fault.mbPerMin * BYTES_PER_MB * TICK_MS / 60_000
        synchronized(retained) {
            retained += ByteArray(bytesPerTick.toInt())
        }
    }

    fun release() {
        synchronized(retained) {
            if (retained.isNotEmpty()) {
                retained.clear()
            }
        }
    }

    fun retainedMb(): Long = synchronized(retained) { retained.sumOf { it.size.toLong() } } / BYTES_PER_MB

    companion object {
        private const val TICK_MS = 5_000L
        private const val BYTES_PER_MB = 1_048_576L
    }
}
