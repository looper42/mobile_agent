package xyz.chouxuewei.mobile_agent.core

/**
 * 模型连接参数保持厂商无关。apiKey 不参与 toString，避免调试日志意外输出密钥。
 */
class ModelConfig(
    val baseUrl: String,
    val model: String?,
    val apiKey: String,
    val reasoningEffortField: String = "reasoning_effort",
) {
    override fun toString(): String =
        "ModelConfig(baseUrl=$baseUrl, model=$model, reasoningEffortField=$reasoningEffortField, apiKey=***)"
}
