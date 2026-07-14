package stillframe42.targetapp.chaos

import org.springframework.stereotype.Component

data class LatencyFault(val delayMs: Long, val percent: Int)

data class ErrorRateFault(val percent: Int)

data class MemoryLeakFault(val mbPerMin: Int)

/**
 * 활성 fault 주입 상태. 의도적으로 인메모리 — 재시작하면 초기화되는 성질에
 * 시나리오 2 의 "재시작으로 회복" 데모가 의존한다 (docs/scenarios.md).
 */
@Component
class ChaosState {

    @Volatile
    var latency: LatencyFault? = null

    @Volatile
    var errorRate: ErrorRateFault? = null

    @Volatile
    var memoryLeak: MemoryLeakFault? = null

    fun reset() {
        latency = null
        errorRate = null
        memoryLeak = null
    }
}
