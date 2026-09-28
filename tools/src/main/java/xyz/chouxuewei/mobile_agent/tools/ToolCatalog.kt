package xyz.chouxuewei.mobile_agent.tools

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import xyz.chouxuewei.mobile_agent.core.ArtifactStore
import xyz.chouxuewei.mobile_agent.core.ConversationStore
import xyz.chouxuewei.mobile_agent.core.DeviceGateway
import xyz.chouxuewei.mobile_agent.core.SkillCreator
import xyz.chouxuewei.mobile_agent.core.ToolRegistry
import xyz.chouxuewei.mobile_agent.core.UserQuestionBroker

object ToolCatalog {
    fun create(
        context: Context,
        conversations: ConversationStore,
        artifacts: ArtifactStore,
        device: DeviceGateway,
        skills: SkillCreator,
        questions: UserQuestionBroker,
    ): ToolRegistry = ToolRegistry(
        listOf(
            HistoryToolProvider(conversations),
            FileToolProvider(context, conversations, artifacts),
            ImageRenderToolProvider(context, artifacts),
            NetworkToolProvider(),
            deviceToolProvider(context, device),
            SkillToolProvider(skills),
            ClipboardToolProvider(context),
            NotificationToolProvider(context),
            SystemToolProvider(context),
            InteractionToolProvider(questions),
        ),
    )

    private fun deviceToolProvider(context: Context, device: DeviceGateway): DeviceToolProvider {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return DeviceToolProvider(device)
        return DeviceToolProvider(
            device,
            DeviceCoordinateDebugStore(File(context.cacheDir, "coordinate-debug")),
        )
    }
}
