package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class ChatRuntimeTest {
    @Test fun supplementDuringCancellationCleanupDoesNotStartOverlappingRun()=runBlocking {
        val memory=MemoryConversationStore()
        val finishing=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val store=object : ConversationStore by memory {
            override suspend fun finishRun(
                run: Run,
                text: String,
                assistantSteps: List<AssistantStep>,
                reasoningDurationMillis: Long?,
                status: RunStatus,
                error: String?,
            ) {
                if(status==RunStatus.CANCELLED) { finishing.complete(Unit); release.await() }
                memory.finishRun(run,text,assistantSteps,reasoningDurationMillis,status,error)
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val started=CompletableDeferred<Unit>(); var calls=0
        val gateway=ChatModelGateway { flow {
            calls++; if(calls==1) { started.complete(Unit); awaitCancellation() }
            emit(ModelEvent.TextDelta("下一轮")); emit(ModelEvent.Completed("stop"))
        } }
        val runtime=ChatRuntime(store,{ _ -> ChatConnection(gateway,ContextPolicy(8192,1024),"test") },scope)
        try {
            runtime.send("c","开始",emptyList()); withTimeout(3000) { started.await() }; runtime.stop("c"); withTimeout(3000) { finishing.await() }
            runtime.send("c","停止之后补充",emptyList())
            assertEquals(1,memory.runs.size)
            release.complete(Unit)
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }
            assertEquals(2,memory.runs.size); assertEquals(RunStatus.SUCCEEDED,memory.runs.last().status)
        } finally { release.complete(Unit); scope.cancel() }
    }
    @Test fun stopKeepsPartialAndProcessesQueuedSupplementWithCorrectOrder()=runBlocking {
        val store=MemoryConversationStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val started=CompletableDeferred<Unit>(); var calls=0
        val requests=mutableListOf<ChatRequest>()
        val gateway=ChatModelGateway { request -> flow {
            requests.add(request); calls++
            if(calls==1) { emit(ModelEvent.TextDelta("部分中文")); started.complete(Unit); awaitCancellation() }
            else { emit(ModelEvent.TextDelta("收到补充")); emit(ModelEvent.Completed("stop")) }
        } }
        val runtime=ChatRuntime(store,{ _ -> ChatConnection(gateway,ContextPolicy(16000,1024),"test") },scope)
        try {
            runtime.send("c","第一条",emptyList()); started.await()
            runtime.send("c","补充",emptyList()); assertTrue(store.history.any { it.status==MessageStatus.QUEUED })
            runtime.stop("c")
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }
            assertEquals(listOf("第一条","部分中文","补充","收到补充"),store.history.map { it.text })
            assertEquals(MessageStatus.CANCELLED,store.history[1].status)
            assertTrue(requests.last().messages.any { it.content=="部分中文" })
            assertEquals(2,store.runs.size)
        } finally { scope.cancel() }
    }
    @Test fun configurationFailureIsRecordedWithoutAutomaticReplay()=runBlocking {
        val store=MemoryConversationStore(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val runtime=ChatRuntime(store,{ _ -> error("没有配置") },scope)
        try {
            runtime.send("c","你好",emptyList())
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }
            assertEquals(1,store.runs.size); assertEquals(RunStatus.FAILED,store.runs.single().status)
            assertFalse(store.history.any { it.status==MessageStatus.QUEUED })
        } finally { scope.cancel() }
    }

    @Test fun recordsLatestReportedUsageOncePerModelRequest()=runBlocking {
        val store=MemoryConversationStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val recorded=mutableListOf<ModelUsageRecord>()
        val gateway=ChatModelGateway { flow {
            emit(ModelEvent.Usage(10,2))
            emit(ModelEvent.Usage(12,4))
            emit(ModelEvent.TextDelta("完成"))
            emit(ModelEvent.Completed("stop"))
        } }
        val runtime=ChatRuntime(
            store=store,
            connection={ _ ->
                ChatConnection(gateway,ContextPolicy(8192,1024),"remote-model","profile-a","模型 A")
            },
            scope=scope,
            usageRecorder={ recorded += it },
        )
        try {
            runtime.send("c","统计用量",emptyList(),modelProfileId="profile-a")
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }
            assertEquals(1,recorded.size)
            assertEquals("profile-a",recorded.single().modelProfileId)
            assertEquals("模型 A",recorded.single().modelName)
            assertEquals("remote-model",recorded.single().modelId)
            assertEquals(12,recorded.single().inputTokens)
            assertEquals(4,recorded.single().outputTokens)
        } finally { scope.cancel() }
    }

    @Test fun configuredMaxStepsStopsFurtherToolRounds()=runBlocking {
        val store=MemoryConversationStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var modelRequests=0
        var toolExecutions=0
        val gateway=ChatModelGateway { flow {
            modelRequests++
            emit(ModelEvent.ToolCall(RequestedToolCall("call-$modelRequests","loop_tool","{}")))
            emit(ModelEvent.Completed("tool_calls"))
        } }
        val provider=countingLoopToolProvider { toolExecutions++ }
        val runtime=ChatRuntime(
            store=store,
            connection={ _ -> ChatConnection(gateway,ContextPolicy(16000,1024),"test") },
            scope=scope,
            tools=ToolRegistry(listOf(provider)),
            maxStepsPerRun={ 2 },
        )
        try {
            runtime.send("c","重复调用工具",emptyList())
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }
            assertEquals(3,modelRequests)
            assertEquals(2,toolExecutions)
            assertEquals(RunStatus.FAILED,store.runs.single().status)
            assertTrue(store.runs.single().error.orEmpty().contains("单轮最大步骤（2）"))
        } finally { scope.cancel() }
    }

    @Test fun unlimitedMaxStepsAllowsToolRoundsUntilModelFinishes()=runBlocking {
        val store=MemoryConversationStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var modelRequests=0
        var toolExecutions=0
        val gateway=ChatModelGateway { flow {
            modelRequests++
            if(modelRequests<=3) {
                emit(ModelEvent.ToolCall(RequestedToolCall("call-$modelRequests","loop_tool","{}")))
                emit(ModelEvent.Completed("tool_calls"))
            } else {
                emit(ModelEvent.TextDelta("完成"))
                emit(ModelEvent.Completed("stop"))
            }
        } }
        val runtime=ChatRuntime(
            store=store,
            connection={ _ -> ChatConnection(gateway,ContextPolicy(16000,1024),"test") },
            scope=scope,
            tools=ToolRegistry(listOf(countingLoopToolProvider { toolExecutions++ })),
            maxStepsPerRun={ UNLIMITED_SINGLE_RUN_MAX_STEPS },
        )
        try {
            runtime.send("c","执行到完成",emptyList())
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }
            assertEquals(4,modelRequests)
            assertEquals(3,toolExecutions)
            assertEquals(RunStatus.SUCCEEDED,store.runs.single().status)
        } finally { scope.cancel() }
    }

    @Test fun selectedSkillIsResolvedAndInjectedOnlyForItsTriggeredRun()=runBlocking {
        val store=MemoryConversationStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val requests=mutableListOf<ChatRequest>()
        val skill=SkillRef("skill-1","version-1","review","Review")
        val gateway=ChatModelGateway { request -> flow {
            requests += request
            emit(ModelEvent.TextDelta("完成"))
            emit(ModelEvent.Completed("stop"))
        } }
        val resolver=object : SkillInstructionResolver {
            override suspend fun resolveSkills(refs: List<SkillRef>) = refs.map {
                assertEquals(skill,it)
                ResolvedSkill(it,"Review code","SKILL-ONLY-MARKER")
            }
        }
        val runtime=ChatRuntime(
            store=store,
            connection={ _ -> ChatConnection(gateway,ContextPolicy(16000,1024),"test") },
            scope=scope,
            skillResolver=resolver,
        )
        try {
            runtime.send("c","第一条",emptyList(),skills=listOf(skill))
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }
            runtime.send("c","第二条",emptyList())
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }

            assertEquals(2,requests.size)
            assertTrue(requests.first().messages.single { it.role=="user" }.content.contains("SKILL-ONLY-MARKER"))
            assertFalse(requests.last().messages.any { it.content.contains("SKILL-ONLY-MARKER") })
            assertEquals(listOf(skill),store.history.first { it.role==MessageRole.USER }.skills)
        } finally { scope.cancel() }
    }
    @Test fun reportedStepResultsStayAvailableAfterSingleStepResultsExpire()=runBlocking {
        val store=MemoryConversationStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val requests=mutableListOf<ChatRequest>()
        var round=0
        val gateway=ChatModelGateway { request -> flow {
            requests += request
            round++
            if(round<=2) {
                emit(ModelEvent.ToolCall(RequestedToolCall("call-$round","loop_tool","{}")))
                emit(ModelEvent.Completed("tool_calls"))
            } else {
                emit(ModelEvent.TextDelta("完成"))
                emit(ModelEvent.Completed("stop"))
            }
        } }
        val runtime=ChatRuntime(
            store=store,
            connection={ _ -> ChatConnection(gateway,ContextPolicy(16000,1024),"test") },
            scope=scope,
            tools=ToolRegistry(listOf(reportingToolProvider(ToolDefinition(
                id="loop_tool",
                title="识别手机界面",
                description="识别当前界面",
                inputSchema="""{"type":"object"}""",
                sideEffect=ToolSideEffect.READ,
                providerId="test",
                requiresPermissionApproval=false,
                resultLifetime=ToolResultLifetime.SINGLE_MODEL_STEP,
            )))),
        )
        try {
            runtime.send("c","继续操作",emptyList())
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }

            assertEquals(3,requests.size)
            val system=requests.last().messages.first { it.role=="system" }.content
            // 上一步申报的收获属于更早的那一步，跨步骤保留在任务记录里。
            assertTrue(system.contains("目的-call-1"))
            assertTrue(system.contains("收获-call-2"))
            // 单步界面结果本身已经过期，但记录没有被一起清掉。
            assertTrue(requests.last().messages.any { it.role=="tool" && it.content.contains("\"expired\":true") })
            // 记录只跟随系统提示，不伪装成用户消息。
            assertFalse(requests.last().messages.any { it.role=="user" && it.content.contains("收获-") })
        } finally { scope.cancel() }
    }

    @Test fun reportedOutcomeReplacesTheCardDetailOfTheStepItDescribes()=runBlocking {
        val store=MemoryConversationStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var round=0
        val gateway=ChatModelGateway { flow {
            round++
            if(round<=2) {
                emit(ModelEvent.ToolCall(RequestedToolCall("call-$round","device_observe","{}")))
                emit(ModelEvent.Completed("tool_calls"))
            } else {
                emit(ModelEvent.TextDelta("完成"))
                emit(ModelEvent.Completed("stop"))
            }
        } }
        val runtime=ChatRuntime(
            store=store,
            connection={ _ -> ChatConnection(gateway,ContextPolicy(16000,1024),"test") },
            scope=scope,
            tools=ToolRegistry(listOf(reportingToolProvider(ToolDefinition(
                id="device_observe",
                title="识别手机界面",
                description="识别当前界面",
                inputSchema="""{"type":"object"}""",
                sideEffect=ToolSideEffect.READ,
                providerId="test",
                requiresPermissionApproval=false,
            )))),
        )
        try {
            runtime.send("c","继续操作",emptyList())
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }

            // 第二步申报的收获描述的是第一步的结果，因此写回第一步的卡片。
            val first=store.toolCallsById.values.single { it.id.endsWith(":call-1") }
            val second=store.toolCallsById.values.single { it.id.endsWith(":call-2") }
            assertEquals("收获-call-2",first.displaySummary)
            assertEquals("完成",second.displaySummary)
        } finally { scope.cancel() }
    }
}

private fun countingLoopToolProvider(onExecute: () -> Unit)=object : ToolProvider {
    override val id="test"
    override val title="测试"
    override val description="测试步骤限制"
    override val definitions=listOf(ToolDefinition(
        id="loop_tool",
        title="循环工具",
        description="持续请求工具以验证单轮上限",
        inputSchema="""{"type":"object"}""",
        sideEffect=ToolSideEffect.READ,
        providerId=id,
        requiresPermissionApproval=false,
    ))
    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult {
        onExecute()
        return ToolResult("{}","完成")
    }
}

private fun reportingToolProvider(definition: ToolDefinition)=object : ToolProvider {
    override val id="test"
    override val title="测试"
    override val description="测试步骤记录"
    override val definitions=listOf(definition)
    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult =
        ToolResult("{}","完成",stepIntent="目的-${call.id}",stepOutcome="收获-${call.id}")
}
