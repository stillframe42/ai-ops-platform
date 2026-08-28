package stillframe42.controlplane.gateway

/** 호출 시점 유효 토큰 공급 계약 — 인터셉터가 캐시 정책을 모르게 하는 경계 */
interface TokenSource {
    fun token(): String
    fun evict()
}
