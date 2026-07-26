package stillframe42.controlplane.alert.controller

import org.springframework.http.HttpStatus
import stillframe42.controlplane.alert.service.AlertIngestService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Alertmanager webhook 수신 (DAY 17) — 수신 즉시 202, 처리는 @Async 로 분리.
 * Alertmanager 는 응답 지연·오류 시 재전송을 반복하므로 수신 확인만 빠르게 돌려준다.
 * 본문은 String 그대로 받는다 — 원본 보존(ops.alerts.raw)이 바인딩보다 우선.
 */
@RestController
class AlertmanagerWebhookController(private val service: AlertIngestService) {

    @PostMapping("/webhook/alertmanager")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun receive(@RequestBody body: String) {
        service.ingestAsync(body)
    }
}
