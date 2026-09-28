package xyz.chouxuewei.mobile_agent.tools

import kotlinx.serialization.json.JsonObject
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext
import xyz.chouxuewei.mobile_agent.core.ToolResult

/** Registry unit for adding a device capability without extending a central dispatch branch. */
internal fun interface DeviceToolHandler {
    suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResult
}
