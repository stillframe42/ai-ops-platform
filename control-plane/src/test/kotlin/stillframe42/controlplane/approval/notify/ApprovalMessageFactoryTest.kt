package stillframe42.controlplane.approval.notify

import com.slack.api.model.block.ActionsBlock
import com.slack.api.model.block.ContextBlock
import com.slack.api.model.block.SectionBlock
import com.slack.api.model.block.composition.MarkdownTextObject
import com.slack.api.model.block.element.ButtonElement
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import stillframe42.controlplane.approval.model.ActionApprovalRequest
import stillframe42.controlplane.approval.model.ApprovalStatus

/**
 * 승인 카드 조립 계약 — HTTP 무의존 순수 함수라 블록 구조를 그대로 검증한다
 * (SlackNotifier buildMessage 테스트와 같은 경계 전략). 버튼 value=incident_id 가
 * Socket Mode 왕복의 키라는 규약이 핵심.
 */
class ApprovalMessageFactoryTest {

    private val incidentId = "inc-error-rate-surge-20260803120000-ab12cd"

    private fun request() = ActionApprovalRequest(
        incidentId = incidentId,
        actionType = "CIRCUIT_BREAK",
        riskLevel = "P2",
        confidence = 0.95,
        requestedAt = Instant.parse("2026-08-03T12:00:00Z"),
        raw = "{}",
        scenario = "error-rate-surge",
        alertName = "TargetAppHighErrorRate",
        rootCauseHypothesis = "외부 의존 오류율 급증",
        actions = listOf("CIRCUIT_BREAK", "NOTIFY_ONLY"),
        rationale = "오류 전파 차단",
        expectedEffect = "오류율 회복",
        risk = "일부 요청 차단",
    )

    @Test
    fun `요청 카드는 요약 섹션과 승인·거부 버튼으로 구성된다`() {
        val blocks = ApprovalMessageFactory.requestBlocks(request())

        assertEquals(2, blocks.size)
        val summary = assertIs<MarkdownTextObject>(assertIs<SectionBlock>(blocks[0]).text).text
        assertTrue(summary.contains("[P2]"))
        assertTrue(summary.contains("error-rate-surge"))
        assertTrue(summary.contains("0.95"))
        assertTrue(summary.contains("CIRCUIT_BREAK, NOTIFY_ONLY"))
        assertTrue(summary.contains("오류 전파 차단"))

        val buttons = assertIs<ActionsBlock>(blocks[1]).elements.map { assertIs<ButtonElement>(it) }
        assertEquals(
            listOf(ApprovalMessageFactory.ACTION_APPROVE, ApprovalMessageFactory.ACTION_REJECT),
            buttons.map { it.actionId },
        )
        // 버튼 value = incident_id — 클릭 페이로드가 이 값으로 decide 대상을 찾는다
        assertTrue(buttons.all { it.value == incidentId })
    }

    @Test
    fun `결정 카드는 버튼 없이 결과 줄을 붙인다 - chat_update 로 중복 클릭 UX 차단`() {
        val blocks = ApprovalMessageFactory.decidedBlocks(
            request(),
            ApprovalStatus.APPROVED,
            decidedBy = "U0123ABC",
            decidedAt = Instant.parse("2026-08-03T12:10:00Z"),
        )

        assertEquals(2, blocks.size)
        assertTrue(blocks.none { it is ActionsBlock })
        val result = assertIs<MarkdownTextObject>(assertIs<ContextBlock>(blocks[1]).elements.single()).text
        assertTrue(result.contains("승인"))
        assertTrue(result.contains("U0123ABC"))
    }

    @Test
    fun `만료 결과는 조치 미실행 종결을 명시한다 - ADR-0006 자동 승인 아님`() {
        val text = ApprovalMessageFactory.resultThreadText(
            ApprovalStatus.EXPIRED,
            decidedBy = "system",
            decidedAt = Instant.parse("2026-08-03T13:00:00Z"),
        )

        assertTrue(text.contains("만료"))
        assertTrue(text.contains("조치 없이 종결"))
    }

    @Test
    fun `재알림 문구는 경과·만료 시각 기준을 담는다`() {
        val text = ApprovalMessageFactory.reminderText(Duration.ofMinutes(30), Duration.ofMinutes(60))

        assertTrue(text.contains("30분"))
        assertTrue(text.contains("60분"))
    }

    @Test
    fun `대체 텍스트는 등급·시나리오·대표 조치를 요약한다`() {
        val text = ApprovalMessageFactory.fallbackText(request())

        assertTrue(text.contains("[P2]"))
        assertTrue(text.contains("error-rate-surge"))
        assertTrue(text.contains("CIRCUIT_BREAK"))
    }
}
