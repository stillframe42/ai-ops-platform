package stillframe42.llmgateway.ratelimit

data class RateLimitDecision(val allowed: Boolean, val retryAfterSeconds: Long = 0)
