package stillframe42.targetapp.chaos

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class ChaosWebConfig(private val chaosInterceptor: ChaosInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        // chaos 주입은 비즈니스 API 한정 — /chaos, /actuator 는 관리 경로라 대상에서 제외 (scenarios.md 공통 요구사항)
        registry.addInterceptor(chaosInterceptor).addPathPatterns("/products", "/products/**")
    }
}
