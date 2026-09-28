package xyz.chouxuewei.mobile_agent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.*

class DeviceExecutionModeTest {
    @Test
    fun malformedCallWithoutModeSafelyFallsBackToBackground() {
        assertEquals(ExecutionMode.VIRTUAL_DISPLAY, resolveDeviceExecutionMode(buildJsonObject {}))
    }

    @Test
    fun explicitBackgroundUsesVirtualDisplay() {
        val args = buildJsonObject { put("mode", "virtual") }
        assertEquals(ExecutionMode.VIRTUAL_DISPLAY, resolveDeviceExecutionMode(args))
    }

    @Test
    fun explicitForegroundUsesMainDisplay() {
        val args = buildJsonObject { put("mode", "main") }
        assertEquals(ExecutionMode.MAIN_DISPLAY, resolveDeviceExecutionMode(args))
    }

    @Test
    fun unknownModeFallsBackToBackground() {
        val args = buildJsonObject { put("mode", "unexpected") }
        assertEquals(ExecutionMode.VIRTUAL_DISPLAY, resolveDeviceExecutionMode(args))
    }

    @Test
    fun openingDeviceDoesNotForceAUserModeChoice() {
        val definition = DeviceToolProvider(UnusedGateway).definitions.single { it.id == "device_open" }
        val required = TOOL_JSON.parseToJsonElement(definition.inputSchema).jsonObject
            .getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(definition.userChoices.isEmpty())
        assertTrue("mode" in required)
        assertFalse(definition.inputSchema.contains("\"default\":"))
        assertTrue(definition.description.contains("任务目标"))
        assertTrue(definition.description.contains("打开或切换应用"))
    }

    @Test
    fun everyDeviceCallRequiresAUserFacingStepSummary() {
        DeviceToolProvider(UnusedGateway).definitions.forEach { definition ->
            val schema = TOOL_JSON.parseToJsonElement(definition.inputSchema).jsonObject
            val summary = schema.getValue("properties").jsonObject.getValue("step_summary").jsonObject
            val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }

            assertEquals("string", summary.getValue("type").jsonPrimitive.content)
            assertEquals(80, summary.getValue("maxLength").jsonPrimitive.content.toInt())
            assertTrue("${definition.id} must require step_summary", "step_summary" in required)
        }
    }

    @Test
    fun everyDeviceCallRequiresTheReportedStepResult() {
        DeviceToolProvider(UnusedGateway).definitions.forEach { definition ->
            val schema = TOOL_JSON.parseToJsonElement(definition.inputSchema).jsonObject
            val result = schema.getValue("properties").jsonObject.getValue("step_result").jsonObject
            val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }

            assertEquals("string", result.getValue("type").jsonPrimitive.content)
            assertEquals(240, result.getValue("maxLength").jsonPrimitive.content.toInt())
            assertTrue("${definition.id} must require step_result", "step_result" in required)
        }
    }

    @Test
    fun reportedStepFieldsSurviveAFailedAction() = runBlocking {
        val session = withDeviceSession()
        val result = session.provider.execute(
            RequestedToolCall(
                "call-1",
                "device_action",
                """{"session_id":"${session.id}","observation_id":"obs-1","action":"back","step_summary":"返回篝火界面","step_result":"牌组视图：28 张卡，其中 5 张已升级"}""",
            ),
            ToolExecutionContext("c", "run-1", "m", "继续"),
        )

        assertTrue(result.isError)
        assertEquals("返回篝火界面", result.stepIntent)
        assertEquals("牌组视图：28 张卡，其中 5 张已升级", result.stepOutcome)
    }

    @Test
    fun emptyOrMissingStepResultCountsAsNotDeclared() = runBlocking {
        val session = withDeviceSession()
        fun declared(arguments: String) = runBlocking {
            session.provider.execute(
                RequestedToolCall("call-1", "device_action", arguments),
                ToolExecutionContext("c", "run-1", "m", "继续"),
            )
        }

        val placeholder = declared(
            """{"session_id":"${session.id}","observation_id":"obs-1","action":"back","step_summary":"返回","step_result":"无"}""",
        )
        val missing = declared(
            """{"session_id":"${session.id}","observation_id":"obs-1","action":"back","step_summary":"返回"}""",
        )

        assertNull(placeholder.stepOutcome)
        assertNull(missing.stepOutcome)
        assertEquals("返回", missing.stepIntent)
    }

    /** 设备动作只有在属于当前执行轮次的会话中才会真正下发，因此先按真实顺序建立会话。 */
    private suspend fun withDeviceSession(): DeviceSession {
        val provider = DeviceToolProvider(SessionExpiringGateway)
        val context = ToolExecutionContext("c", "run-1", "m", "继续")
        val opened = provider.execute(
            RequestedToolCall("call-open", "device_open", """{"mode":"main","step_summary":"开始操作"}"""),
            context,
        )
        val id = TOOL_JSON.parseToJsonElement(opened.content).jsonObject.getValue("session_id").jsonPrimitive.content
        return DeviceSession(provider, id)
    }

    private class DeviceSession(val provider: DeviceToolProvider, val id: String)

    private object SessionExpiringGateway : DeviceGateway {
        override suspend fun openSession(mode: ExecutionMode): DeviceResult<ExecutionSession> =
            DeviceResult.Success(ExecutionSession("s-1", mode, setOf(DeviceCapability.SCREENSHOT)))

        override suspend fun observe(sessionId: String): DeviceResult<Observation> = error("unused")

        override suspend fun execute(sessionId: String, observationId: String?, action: Action): ActionResult =
            ActionResult.SessionExpired("会话已关闭")

        override suspend fun closeSession(sessionId: String): DeviceResult<Unit> = error("unused")
    }

    private object UnusedGateway : DeviceGateway {
        override suspend fun openSession(mode: ExecutionMode): DeviceResult<ExecutionSession> = error("unused")
        override suspend fun observe(sessionId: String): DeviceResult<Observation> = error("unused")
        override suspend fun execute(sessionId: String, observationId: String?, action: Action): ActionResult = error("unused")
        override suspend fun closeSession(sessionId: String): DeviceResult<Unit> = error("unused")
    }
}
