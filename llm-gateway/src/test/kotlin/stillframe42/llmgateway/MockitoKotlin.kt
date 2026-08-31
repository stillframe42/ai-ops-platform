package stillframe42.llmgateway

import org.mockito.ArgumentMatchers

/**
 * Mockito `any()` 는 null 을 돌려주는데 Kotlin non-null 파라미터에 넘기면 호출 지점에서 NPE 가 난다 (8/28 실측) —
 * 반환 타입을 제네릭 T 로 선언해 호출 지점의 null 검사를 우회한다 (매처 등록 부수효과는 그대로).
 */
@Suppress("UNCHECKED_CAST")
fun <T> anyNonNull(): T {
    ArgumentMatchers.any<T>()
    return null as T
}
