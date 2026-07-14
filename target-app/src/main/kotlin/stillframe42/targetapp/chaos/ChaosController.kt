package stillframe42.targetapp.chaos

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * fault-injection 관리 API (docs/scenarios.md FI-1~4).
 */
@RestController
@RequestMapping("/chaos")
class ChaosController(
    private val chaosState: ChaosState,
    private val memoryLeakSimulator: MemoryLeakSimulator,
) {

    @PostMapping("/latency")
    fun injectLatency(
        @RequestParam ms: Long,
        @RequestParam(defaultValue = "100") percent: Int,
    ): Map<String, Any?> {
        require(ms > 0) { "ms 는 양수여야 합니다: $ms" }
        requireValidPercent(percent)
        chaosState.latency = LatencyFault(ms, percent)
        return status()
    }

    @PostMapping("/error-rate")
    fun injectErrorRate(@RequestParam percent: Int): Map<String, Any?> {
        requireValidPercent(percent)
        chaosState.errorRate = ErrorRateFault(percent)
        return status()
    }

    @PostMapping("/memory-leak")
    fun injectMemoryLeak(@RequestParam mbPerMin: Int): Map<String, Any?> {
        require(mbPerMin > 0) { "mbPerMin 은 양수여야 합니다: $mbPerMin" }
        chaosState.memoryLeak = MemoryLeakFault(mbPerMin)
        return status()
    }

    @PostMapping("/reset")
    fun reset(): Map<String, Any?> {
        chaosState.reset()
        memoryLeakSimulator.release()
        return status()
    }

    @GetMapping
    fun status(): Map<String, Any?> = mapOf(
        "latency" to chaosState.latency,
        "errorRate" to chaosState.errorRate,
        "memoryLeak" to chaosState.memoryLeak,
        "retainedMb" to memoryLeakSimulator.retainedMb(),
    )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidParam(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    private fun requireValidPercent(percent: Int) {
        require(percent in 1..100) { "percent 는 1~100 이어야 합니다: $percent" }
    }
}
