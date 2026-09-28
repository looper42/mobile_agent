package xyz.chouxuewei.mobile_agent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private object UnusedGateway : DeviceGateway {
        override suspend fun openSession(mode: ExecutionMode): DeviceResult<ExecutionSession> = error("unused")
        override suspend fun observe(sessionId: String): DeviceResult<Observation> = error("unused")
        override suspend fun execute(sessionId: String, observationId: String?, action: Action): ActionResult = error("unused")
        override suspend fun closeSession(sessionId: String): DeviceResult<Unit> = error("unused")
    }
}
