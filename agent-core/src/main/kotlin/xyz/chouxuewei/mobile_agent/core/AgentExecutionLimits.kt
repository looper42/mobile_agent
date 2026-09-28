package xyz.chouxuewei.mobile_agent.core

const val UNLIMITED_SINGLE_RUN_MAX_STEPS = -1
const val DEFAULT_SINGLE_RUN_MAX_STEPS = UNLIMITED_SINGLE_RUN_MAX_STEPS
const val MIN_SINGLE_RUN_MAX_STEPS = 1

/**
 * 单轮最大步骤限制连续工具调用轮数；-1 表示不限制。集中校验可以避免设置页、持久化层和运行时采用不同规则。
 */
fun isValidSingleRunMaxSteps(value: Int): Boolean =
    value == UNLIMITED_SINGLE_RUN_MAX_STEPS || value >= MIN_SINGLE_RUN_MAX_STEPS

fun requireValidSingleRunMaxSteps(value: Int): Int {
    require(isValidSingleRunMaxSteps(value)) {
        localizedText(
            "单轮最大步骤需要为 -1（无上限）或不小于 $MIN_SINGLE_RUN_MAX_STEPS",
            "Maximum steps per run must be -1 (unlimited) or at least $MIN_SINGLE_RUN_MAX_STEPS.",
        )
    }
    return value
}
