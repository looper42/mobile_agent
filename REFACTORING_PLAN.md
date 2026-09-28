# Mobile Agent 重构计划

> 状态：R00～R10 与 R12 已实施；R11 按要求排除  
> 编制日期：2026-09-28  
> 适用分支基线：`dev`  
> 计划范围：性能、可靠性、扩展性、可维护性；不改变现有产品功能边界

> 实施记录：本轮按用户要求只执行编译验证，未运行单元测试、instrumentation、lint 或性能基准。因此文中倍数和百分比仍是验收目标，不是实测结论。

## 0. 实施结果摘要

| 范围 | 结果 |
| --- | --- |
| R00～R04 | 增加可重复编译入口；流式回复改为内存态 + 节流检查点；草稿 latest-wins；共享 HTTP 客户端；SSE 和图片请求全链路有界，Base64/JSON 改为流式写入。 |
| R05～R06 | 热路径改为专用 DAO；历史搜索改为 FTS4 + 显式迁移/触发器；聊天页收敛为会话、环境、时间线三个聚合状态，设置页为一个聚合状态。 |
| R07～R09 | 拆出悬浮状态、窗口、通知和预览控制器；上下文估算/组装/校验/规划/摘要分层；同会话串行、跨会话隔离、3 路全局上限、5 秒有界清理。 |
| R10 | 删除无产品入口的 `AgentRunner`/旧模型网关/TaskStore 双路径，数据库 8→9 显式删除旧 `tasks`/`steps` 表。 |
| R11 | 未实施；不在本轮范围。 |
| R12 | 设备命令改为进程级有界执行器，stdout/stderr 并发排空，超时分级终止；等待使用单调时钟；截图加像素/输入/输出/并发限额；工具分派改为 handler registry。 |

## 1. 结论与建议

当前模块依赖方向总体合理，真正需要优先处理的不是“重新分层”，而是以下四条高成本链路：

1. 模型每个流式增量都会触发完整回复和步骤的持久化，进一步放大 Room、Flow、Compose 和通知更新。
2. `ChatRuntime`、`ChatApp`、`DeviceOperationOverlayService` 同时承担过多职责，状态边界不清，任何修改都容易扩大回归范围。
3. 网络流和图片请求缺少完整的资源上限，客户端生命周期也没有复用，存在卡死、内存峰值和重复建连风险。
4. 长会话路径仍大量使用“读取全集后在内存中过滤/拼接”，数据规模增大后会同时影响延迟和内存。

建议按“先建立基线、再切断写放大、随后优化数据/UI、最后拆分大类”的顺序实施。核心收益路径预计需要 **17～28 人日**；包含设备执行硬化、结构拆分、旧路径清理和工程治理的完整计划预计需要 **30～49 人日**。单人串行约 6～10 周，两人按网络/数据与 UI/服务两个轨道并行约 4～7 周。

所有百分比和倍数均为工程目标，不是当前已测结论；必须由第 4 节定义的基准场景验证。不同项目的收益不能直接相加。

## 2. 重构前基线与证据（保留作为决策记录）

### 2.1 代码规模

| 项目 | 当前值 | 说明 |
| --- | ---: | --- |
| 生产 Kotlin 文件 | 74 | 共约 19,628 行 |
| 测试 Kotlin 文件 | 42 | 共约 2,812 行 |
| CodeGraph 索引 | 132 文件 / 3,818 节点 / 9,673 边 | 2026-09-28 检查为最新 |
| `ChatApp.kt` | 2,596 行 | 根 Composable 同时订阅大量状态并承载多类 UI |
| `ChatSettings.kt` | 2,186 行 | 设置页面和领域设置高度集中 |
| `OverlayContent.kt` | 1,405 行 | 悬浮窗多形态 UI 集中 |
| `DeviceOperationOverlayService.kt` | 1,207 行 | 生命周期、状态、窗口、通知、语音和预览集中 |
| `ChatRuntime.kt` | 791 行 | 队列、模型、工具、压缩、恢复、图片和持久化集中 |

### 2.2 生产主链路

```text
PrototypeApplication
  ├─ ChatWorkspace ────────────────┐
  ├─ ChatRuntime                   │
  │    ├─ ContextManager           │
  │    ├─ OpenAiChatGateway        │
  │    ├─ ToolRegistry/Providers   │
  │    └─ ConversationStore ─ Room │
  ├─ ChatApp ◀─────────────────────┤
  └─ DeviceOperationOverlayService ◀┘
```

静态影响分析显示：

- `ChatRuntime` 变更可影响至少 112 个符号，并直接关联核心单元测试和多项 Android 验收测试。
- `DeviceOperationOverlayService` 变更可影响至少 156 个符号，且目前针对 Service 编排本身的测试不足。
- `OpenAiChatGateway` 变更可影响 `ChatRuntime`、`ContextManager`、真实模型验收和网关单元测试。
- 对六个核心热点文件做受影响测试分析时，CodeGraph 返回 24 个测试文件；静态图不能覆盖依赖注入、Android 生命周期和反射路径，因此下文额外列出人工补充测试。

### 2.3 已确认的热点

| 编号 | 现状 | 主要后果 | 证据位置 |
| --- | --- | --- | --- |
| H1 | 每个 `TextDelta` / `ReasoningDelta` 都构造完整字符串和步骤快照并写入 Room | 近似 O(n²) 的复制、数据库写放大、Flow 失效和 UI 重组 | `ChatRuntime.kt:203-217`、`RoomConversationStore.kt:64-65` |
| H2 | 草稿编辑通过 `Channel.UNLIMITED` 逐次持久化 | 快速输入时积压命令，写入旧状态，退出时延迟不可控 | `ChatWorkspace.kt:22,52-62` |
| H3 | 全局互斥锁内执行数据库 I/O | 一个会话的慢 I/O 可阻塞其它会话发送、停止和审批 | `ChatRuntime.kt:101-107,129-135` |
| H4 | 每次聊天运行创建新的流式 `OkHttpClient` | 连接池、TLS 会话和线程池不能稳定复用 | `PrototypeApplication.kt:60-80`、`OpenAiChatGateway.kt:24-28` |
| H5 | SSE 单行、累计正文、推理内容、工具参数和工具数量缺少完整上限；读超时和调用超时均为 0 | 恶意或异常服务可造成无限内存增长或永久挂起 | `OpenAiChatGateway.kt:210-286,359-362` |
| H6 | 图片先转 Base64 字符串，再构建 JSON DOM、payload 字符串和请求体 | 多份大对象同时驻留，20 MB 原始输入可放大到 100 MB 级峰值 | `ChatRuntime.kt:696-719`、`OpenAiChatGateway.kt:98-125` |
| H7 | 消息/工具 Flow 更新会同时发布悬浮状态并刷新通知 | 流式输出时可能接近逐 token 调用系统通知 | `DeviceOperationOverlayService.kt:386-405,1031-1033` |
| H8 | `ChatApp` 分别订阅二十余条 Flow；时间线每次重组反复过滤全部调用和产物 | 长会话和流式输出时主线程工作量放大 | `ChatApp.kt:155-198,1091-1096` |
| H9 | 多处读取全部消息后求最大序号、定位消息、判断队列；搜索最多加载 1,000 条消息后在 Kotlin 过滤 | 长历史延迟随数据量线性增长，且搜索存在完整性上限 | `RoomConversationStore.kt:40-61,113-129` |
| H10 | 上下文切块和 token 估算反复重扫已拼接字符串，并对每条消息过滤全部工具历史 | 长文本压缩路径出现 O(n²) 或 O(messages × tools) 工作 | `ContextManager.kt:279-330` |
| H11 | 旧 `AgentRunner` / task 存储路径未进入当前生产主链路 | 两套执行语义增加理解、测试和扩展成本 | `AgentRunner.kt`、`AgentDatabase.kt` 及相关测试 |
| H12 | 全局允许明文 HTTP；发布构建未开启压缩；Room 不导出 schema | 数据安全、包体、迁移审计能力不足 | `network_security_config.xml:4`、`app/build.gradle.kts:69-86`、`AgentDatabase.kt:62-65` |

## 3. 目标、边界和优先级

### 3.1 目标

- 流式输出频率与持久化频率解耦，最终态、取消态和错误态仍可可靠恢复。
- 任何单个会话、网络流或工具清理异常都不能无限阻塞其它会话。
- 长会话的发送、搜索、压缩和渲染成本尽量接近线性或按需查询。
- UI 只消费稳定的页面状态，不直接承担跨仓库业务编排。
- 新模型、新工具、新悬浮形态优先通过窄接口扩展，不修改核心循环。
- 数据库迁移、进程重启、取消和异常网络成为强制回归场景。

### 3.2 非目标

- 不在本计划中重写 Compose UI 或改变产品交互。
- 不更换 Room、OkHttp、Coroutines 等基础技术栈。
- 不为“文件更小”而机械拆类；拆分必须对应独立状态、生命周期或测试边界。
- 不同时大改模型协议、工具协议和数据库 schema；跨边界修改必须分阶段落地。
- 不在没有基准数据时承诺绝对毫秒值。

### 3.3 优先级定义

| 优先级 | 定义 | 合并要求 |
| --- | --- | --- |
| P0 | 已存在资源失控、数据风险或明显写放大 | 建议优先于新功能；必须有故障测试和回滚路径 |
| P1 | 直接限制长会话性能或核心结构可维护性 | 当前迭代完成，必须有性能对比或结构测试 |
| P2 | 降低后续扩展成本和回归范围 | 可分批落地，保持外部契约兼容 |
| P3 | 工程治理和进一步收敛 | 在 P0/P1 稳定后实施 |

### 3.4 收益评分

计划表中的“性能/可靠性/扩展性/可维护性”采用 1～5 分：1 为轻微，3 为明显，5 为结构性改善。评分表示相对优先级，不表示精确收益，也不能相加。

## 4. 先建立可比较的基准

在开始重构前完成 R00，否则无法判断“优化”是否真实发生。

| 指标 | 固定场景 | 采集方式 | 目标门槛 |
| --- | --- | --- | --- |
| 流式持久化写次数 | 10 秒内注入 1,000 个文本/推理 delta，含 10 次步骤变化 | Fake Store 计数 + Room DAO 计数 | 写入次数降低 ≥90%；最终内容和步骤 100% 一致 |
| 流式内存与 GC | 生成 10,000 字回复并持续展示 | Macrobenchmark / Perfetto / heap 采样 | 分配量降低 ≥50%，无随 token 数量二次增长趋势 |
| 草稿写次数 | 30 秒输入 300 次，包含暂停、切换会话和立即发送 | Repository spy | 写入次数降低 ≥90%；切换/发送/退出不丢最后版本 |
| 通知更新次数 | 前台服务收到 1,000 个 delta、一次审批、一次完成 | Notification gateway spy | delta 不直接触发通知；总调用降低 ≥90% |
| 图片请求峰值 | 1、5、10 张接近上限的图片 | Android Studio Profiler / heap dump | 峰值堆降低 30%～60%，超限请求在组包前失败 |
| 历史搜索 | 100、1,000、10,000 条消息，命中首/中/末/无结果 | Room benchmark | 10,000 条场景 p95 提升 5～20 倍；结果不再受 1,000 条上限影响 |
| 长上下文压缩 | 100k、500k、1M 字符，含多次工具调用 | JVM benchmark | CPU 时间提升 3～10 倍；临时字符串显著下降 |
| 多会话隔离 | 两个会话并发，其中一个 DAO 人工延迟 2 秒 | coroutine test | 另一会话的停止/审批不被延迟 2 秒 |
| SSE 失效恢复 | 无首字节、半包停滞、超大行、超大工具参数、断流 | MockWebServer | 有界超时、有界内存、部分输出保留、错误不泄露密钥 |
| 冷启动/恢复 | 生成中、工具执行中、压缩中分别终止进程再启动 | instrumentation | 不重复执行未知副作用；消息状态和可恢复内容正确 |

基准结果保存到 `docs/performance/`，至少包含设备、构建类型、数据规模、运行次数、p50/p95、峰值内存和变更前后提交号。性能测试采用同一设备、关闭动画和相同温度区间，避免只比较单次结果。

## 5. 总体重构计划表

| ID | 优先级 | 工作项 | 主要模块 | 依赖 | 人日 | 性能 | 可靠性 | 扩展性 | 可维护性 |
| --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| R00 | P0 | 基准、计数器与故障注入 | 全模块/测试 | 无 | 1～2 | 2 | 4 | 2 | 4 |
| R01 | P0 | 流式内存态与持久化检查点分离 | `agent-core`、`data` | R00 | 3～4 | 5 | 5 | 4 | 4 |
| R02 | P0 | 草稿保存合并、屏障与关闭语义 | `app`、`data` | R00 | 1～2 | 4 | 4 | 3 | 4 |
| R03 | P0 | 共享 HTTP 客户端与 SSE 资源护栏 | `model`、`app` | R00 | 2～3 | 4 | 5 | 4 | 4 |
| R04 | P0 | 图片请求流式组包与全链路配额 | `agent-core`、`model`、`app` | R03 | 1～2 | 5 | 4 | 3 | 3 |
| R05 | P1 | 专用 DAO、投影查询与 FTS 搜索 | `data`、`agent-core` | R00、R01 | 3～5 | 5 | 4 | 4 | 4 |
| R06 | P1 | 聚合 `ChatUiState` 与时间线索引 | `app` | R01、R05 | 4～6 | 4 | 4 | 4 | 5 |
| R07 | P1 | 悬浮服务拆分为状态、窗口、通知和预览控制器 | `app` | R01、R06 | 4～6 | 4 | 5 | 5 | 5 |
| R08 | P1 | `ContextManager` 算法和职责拆分 | `agent-core` | R00、R03 | 2～4 | 5 | 4 | 5 | 5 |
| R09 | P2 | `ChatRuntime` 编排拆分、锁粒度和并发上限 | `agent-core`、`app` | R01、R03、R05、R08 | 3～5 | 4 | 5 | 5 | 5 |
| R10 | P2 | 隔离或删除旧 Agent 执行路径 | `agent-core`、`data`、测试 | R09 | 1～2 | 1 | 4 | 4 | 5 |
| R11 | P1/P3 | 安全、数据库迁移与构建质量门禁 | `app`、`data`、Gradle | R00；R05 后收尾 | 2～3 | 2 | 5 | 3 | 5 |
| R12 | P2 | 设备与工具执行硬化 | `device`、`tools`、`agent-core` | R00、R09 | 3～5 | 3 | 5 | 5 | 4 |

## 6. 分项实施说明

### R00：基准、计数器与故障注入

**目标**：在不改变生产行为的前提下，得到可重复的性能和可靠性基线。

实施内容：

- 为 `ConversationStore` 测试替身增加 `updateReply`、`saveDraft`、查询次数和写入字节计数。
- 把 `NotificationManager.notify` 包装为可替换的 `OverlayNotificationSink`，先保持原行为。
- 给模型网关测试增加可控时钟、半包停顿、超大行、永不结束响应和断流场景。
- 建立长历史 fixture：100、1,000、10,000 条消息和密集工具记录。
- 建立图片请求 heap 场景和长上下文 JVM benchmark。
- 记录当前构建失败环境：本机执行 Gradle 在项目任务开始前出现 `java.io.IOException: Unable to establish loopback connection`。先固化 `docs/PLAN.md` 已提到的进程级 JDK SelectorProvider/临时 socket 目录方案，再把“全量测试通过”作为后续合并门禁；在解决前不得声称回归已通过。

验收条件：同一场景连续运行至少 5 次，保留原始数据；计数器本身不改变业务时序；CI 或本地 runbook 能一键重现。

### R01：流式内存态与持久化检查点分离

**目标设计**：UI 读取高频内存态，Room 保存低频恢复检查点和强制最终态。

建议新增：

- `StreamingReplySession`：拥有文本、推理、步骤和版本号，只在单个会话运行期存在。
- `ReplyCheckpointWriter`：按时间和变更量合并写入；同一会话最多一个待写检查点。
- `ReplySnapshot`：一次不可变快照，避免文本和步骤来自不同版本。

实施步骤：

1. 先保留 `ConversationStore.updateReply` 契约，在其上实现检查点写入器，避免第一步同时改数据库接口。
2. `TextDelta` / `ReasoningDelta` 只更新 session 并发布内存态，不直接调用 Store。
3. 默认每 100～150 ms 或累计达到阈值时写检查点；实际阈值由 R00 基准决定。
4. 完成、取消、错误、进入工具调用前、应用退后台和进程可控关闭时执行强制 flush。
5. flush 采用快照版本去重，旧写入不能覆盖新版本；失败写入保留下一次重试机会。
6. 检查点写失败不应中止已经收到的模型输出；最终状态写失败要记录可诊断错误。

验收条件：

- 1,000 delta 场景 Store 写次数至少减少 90%，最终内容逐字符一致。
- `stop`、网络断流、工具审批、压缩触发、进程恢复均保留最后一个已确认检查点。
- 补充消息在取消清理期间不会启动重叠运行。
- 新增 `ReplyCheckpointWriterTest`；回归 `ChatRuntimeTest`、`ChatColdStartTest`、`ChatInteractionTest`、`ChatAcceptanceTest`。

回滚方式：保留“每 delta 立即写”的内部策略开关一个发布周期，仅用于紧急回退；稳定后删除开关，避免形成双路径。

预期收益：Room 写入减少 80%～99%，由写入导致的 Flow 失效和重组减少 50%～90%，长回复分配从近似二次增长收敛到接近线性。

### R02：草稿保存合并、屏障与关闭语义

**目标设计**：内存状态立即响应，持久化按会话合并；发送、切换、退后台是 flush 屏障。

实施步骤：

- 删除 `Channel.UNLIMITED` 的逐编辑命令模型，改为每会话 `StateFlow`/actor 的 latest-wins 保存。
- 采用 200～300 ms debounce；附件变化可立即或短 debounce 保存。
- `send`、`select`、`acceptShare`、`onStop` 前等待当前版本落盘。
- 每个草稿携带单调版本，防止慢写入覆盖较新内容。
- 关闭时使用有界 flush；失败保留内存草稿并显示可恢复错误。

验收条件：300 次编辑写入次数减少至少 90%；发送文本与最后可见草稿一致；快速切换两个会话不串稿；进程重建测试通过。

### R03：共享 HTTP 客户端与 SSE 资源护栏

**目标设计**：Application 级共享连接资源，每个请求拥有独立取消与空闲超时；所有输入流均有明确上限。

实施步骤：

- 在 `PrototypeApplication` 构造并注入共享 `OkHttpClient`，网关不再通过便捷构造器隐式创建客户端。
- 连接池、Dispatcher 和 TLS 会话由应用共享；认证头仍按请求生成，不能缓存 API Key 到日志。
- 实现有界 SSE 行读取，避免先 `readUtf8Line()` 再检查长度。
- 分别限制：单事件、累计正文、累计推理、单工具参数、全部工具参数、工具调用数量和响应总字节。
- 增加首字节超时和“连续无数据”空闲超时；不要用过短的总调用超时截断正常长回复。
- 仅允许在尚未发布任何 delta 且错误可重试时自动重试；一旦已有输出，保留部分内容并明确失败，避免重复语义。
- 网络错误文本必须继续屏蔽密钥、认证头和原始服务端敏感正文。

验收条件：MockWebServer 覆盖 UTF-8 跨包、超大行、停滞、半包断开、取消、畸形事件、工具参数超限和无输出重试；所有场景都有确定结束时间和内存上限。

预期收益：后续请求通常减少几十到数百毫秒的重复建连/TLS 成本；资源耗尽和永久挂起风险显著下降。

### R04：图片请求流式组包与全链路配额

**目标设计**：只保留必要的一份图片数据，编码时直接写入请求 sink，并在读取/解码/编码前后都执行预算检查。

实施步骤：

- 将当前 20 MB 聚合原始图片上限调整为经过设备 heap 基准验证的预算，并增加图片数量、单图像素和编码后预算。
- 复用 `AttachmentManager` 已有缩放结果；请求阶段不得再次持有不必要的原图 Bitmap。
- 自定义流式 `RequestBody`，直接向 Okio sink 写 JSON 和 Base64，避免 Base64 String、JSON DOM 和 payload String 三重驻留。
- 溢出时在网络调用前返回可操作错误，列出数量/大小限制，不尝试部分静默丢图。
- CPU 密集型压缩使用 `Dispatchers.Default`，文件读取使用 `Dispatchers.IO`。

验收条件：1/5/10 图场景输出协议与现有服务兼容；峰值 heap 降低 30%～60%；取消能及时停止编码并释放 Bitmap/流。

### R05：专用 DAO、投影查询与 FTS 搜索

**目标设计**：业务意图映射到专用 SQL，不为一个标量结果加载完整会话；搜索由数据库索引完成。

建议新增 DAO：

- `maxSequence(conversationId)`
- `messageById(messageId)`
- `firstQueuedMessage(conversationId)`
- `hasQueuedMessage(conversationId)`
- 会话列表和搜索结果的窄投影
- Room FTS 表：标题、用户文本、助手文本；明确是否索引推理和工具大结果，默认不索引敏感/高体积字段

实施步骤：

1. 先添加专用查询并保持 Store 接口兼容，逐个替换全量加载。
2. 增加 FTS schema 和显式迁移，回填已有消息；禁止 destructive migration。
3. 搜索分页并返回 snippet，不再把最多 1,000 条完整消息加载到 Kotlin 后排序。
4. 启用 Room schema 导出，版本化保存 schema，并添加迁移测试。
5. 为多字段排序、大小写、中文、特殊符号和空查询定义确定行为。

验收条件：10,000 条消息场景结果完整，p95 目标提升 5～20 倍，查询分配内存降低 80% 以上；`ConversationMigrationTest` 验证旧数据库升级和 FTS 回填。

### R06：聚合 `ChatUiState` 与时间线索引

**目标设计**：页面只订阅少量生命周期感知状态；关联数据在 ViewModel/Presenter 中预计算。

建议结构：

```text
ChatViewModel / ChatPresenter
  ├─ ChatUiState                 页面级稳定状态
  ├─ ConversationTimelineState   messages + callsByReply + artifactsByReply
  ├─ ComposerUiState             draft + attachments + send/stop capability
  └─ PendingInteractionState     approval + question + share
```

实施步骤：

- 将 `ChatApp` 中二十余个独立 `collectAsState` 收敛为 3～5 个聚合状态，并使用 `collectAsStateWithLifecycle`。
- 用 `stateIn(WhileSubscribed)` 控制订阅生命周期，避免页面不可见时仍做 UI 派生计算。
- 在上游用 `groupBy(replyMessageId)` 或增量索引生成工具调用/产物映射，消除逐消息全表过滤。
- 保持事件单向：UI 发送 intent，ViewModel 调用 Workspace/Runtime；Composable 不直接编排仓库。
- 先迁移时间线，再迁移侧栏、输入区和设置入口；每一步保持截图/语义测试稳定。
- `ChatApp.kt` 最终只保留主题、导航壳和页面装配；按功能拆文件，不按任意行数拆分。
- 将 `ChatSettings.kt` 按模型、外观、工具、语音和高级设置拆成独立 screen/state holder；公共表单、校验和保存状态复用组件，避免一个设置变化触发整个设置树重组。

验收条件：长回复场景重组次数和主线程工作量下降 30%～70%；切主题、旋转、前后台、切会话不丢滚动、焦点、草稿和审批状态。

### R07：悬浮服务拆分

**目标设计**：Service 只拥有 Android 生命周期，业务状态、WindowManager、通知和虚拟屏预览各有独立控制器。

建议结构：

```text
DeviceOperationOverlayService       生命周期、foreground type、依赖装配
  ├─ OverlayStateStore              reduce 输入事件，输出 OverlayUiState
  ├─ OverlayWindowController        add/update/remove、拖动、缩放、边缘吸附
  ├─ OverlayNotificationController  有意义状态投影、distinct、节流
  ├─ VirtualScreenPreviewController 预览采样和释放
  └─ OverlayActionDispatcher        发送、停止、批准、回答、打开页面
```

实施步骤：

1. 先抽出通知 sink 和 `NotificationState`，只在标题、运行状态、待审批/问题、前台类型变化时刷新；普通 token 不参与通知状态。
2. 将现有可测试的状态机迁入 `OverlayStateStore`，Service 只收集输出。
3. 抽出窗口控制器，统一坐标约束、尺寸和 add/remove 幂等行为。
4. 抽出虚拟屏预览及其采样生命周期，`onDestroy` 必须有界释放。
5. 最后瘦身 Service；不要一次性搬迁 1,200 行后再补测试。
6. `OverlayContent.kt` 按紧凑态、摘要态、完整态和交互面板拆为纯展示组件；状态机仍只有一个来源，不能在各 Composable 内复制业务状态。

验收条件：1,000 delta 场景通知调用降低 90% 以上；旋转、权限撤销、Service 重建、重复 start/stop、窗口 add 失败和预览异常均不会泄漏窗口或重复前台通知。

### R08：`ContextManager` 算法和职责拆分

**目标设计**：token 估算、上下文组装、快照校验、压缩规划和摘要生成分别可测试；热路径避免重复扫描。

建议拆分：

- `TokenEstimator`
- `ConversationAssembler`
- `SnapshotValidator`
- `CompactionPlanner`
- `ConversationSummarizer`

实施步骤：

- 预先按消息/调用 ID 索引工具历史，避免每条消息全量 filter。
- 文本切块使用增量 token 估算或二分边界，不反复执行 `chunk.toString() + part`。
- 用 builder/段列表延迟合并字符串；把预算检查和最终序列化分开。
- 保持现有语义：用户纠正优先、近期原文保留、摘要视为低信任数据、来源版本变化拒绝发布。
- 为极长单消息、Unicode 代理对、工具链断裂、并发追加和重复压缩添加性质测试。

验收条件：长上下文 benchmark 提升 3～10 倍或更多；短会话无明显回退；`ContextManagerTest` 和 `ContextAcceptanceTest` 保持全部语义断言。

### R09：`ChatRuntime` 编排拆分、锁粒度和并发上限

**目标设计**：Runtime 是薄门面，每个会话串行、不同会话隔离，全局只维护必要索引。

建议结构：

```text
ChatRuntime                  对外 API 与状态汇总
  ├─ ConversationRunCoordinator  每会话队列、Job、状态机
  ├─ ModelTurnExecutor            模型请求和流事件处理
  ├─ ToolCallExecutor             校验、授权、调用、结果生命周期
  ├─ RunRecovery                  启动恢复与未知副作用策略
  └─ RunConcurrencyLimiter        全局可配置并发上限
```

实施步骤：

- 用每会话 mutex/actor 保证顺序；全局锁只保护 coordinator map，不在锁内执行 Store、Gateway 或 Tool I/O。
- `send` 先完成数据库写，再用短临界区登记/唤醒会话；失败时状态一致可解释。
- `stop` 和审批完成不等待其它会话 I/O。
- 增加全局 semaphore，默认同时运行 2～3 个模型会话，数量通过设备和服务限流基准确定。
- `NonCancellable` 清理增加合理超时；超时记录清理失败并释放运行槽，不能无限保留 Job。
- 工具可用性和权限读取先获取一次快照；独立 provider 的 availability 可在明确线程安全时并行。

验收条件：DAO 人工阻塞 2 秒不影响其它会话 stop/approve；同一会话绝不重叠运行；达到并发上限时保持可见 queued 状态；取消、补充消息和恢复测试通过。

### R10：隔离或删除旧 Agent 执行路径

**决策门**：先用构建引用、运行入口和产品路线确认 `AgentRunner`、`OpenAiCompatibleModelGatewayFactory`、task/step 表是否仍承担兼容职责。

- 若无生产用途：删除实现、无效 schema 和仅为旧路径存在的测试；数据库表删除通过显式迁移完成。
- 若仍需实验：移动到明确的 `:experimental-agent` 模块，默认不由 `app` 依赖，不共享生产数据库状态机。
- 禁止继续维护两套“模型循环 + 工具执行 + 恢复”语义。
- 顺手修正 `AgentRunRequest` 的范围和错误文案不一致问题，但不得把小修复当作保留整条旧路径的理由。

验收条件：生产依赖图只剩一条聊天执行主链；APK 不包含未使用入口；数据库升级不会丢失仍需保留的数据。


### R12：设备与工具执行硬化

**目标设计**：设备命令、等待、截图和工具定义分别有界、可取消、可测试；保留现有“单模型步骤结果生命周期”的正确设计。

实施步骤：

- `DeviceCommands` 不再为每条命令创建新的单线程 executor；改用进程级有界执行器或结构化协程，并限制并发命令数量。
- 命令超时先正常终止，短宽限后 `destroyForcibly()`；始终并发排空 stdout/stderr，避免子进程因管道写满而死锁。
- 所有等待 deadline 使用 `SystemClock.elapsedRealtime()`，避免系统时间调整导致提前超时或无限等待。
- 截图读取仍在 `Dispatchers.IO`，像素转换/JPEG 压缩转到 `Dispatchers.Default`；加入像素数、压缩输出和同时在途截图数量限制。
- 将超过 1,000 行的 `DeviceToolProvider` 按观察、动作、等待、虚拟屏和系统能力拆为小 provider/handler，并通过同一 registry 注册。
- 工具权限和启用状态一次获取不可变快照；provider availability 只有在明确线程安全时并行检查。
- 每次设备会话清理使用有界超时，并记录未成功清理的资源；不能因清理挂起占用全局运行槽。

验收条件：命令超时后无遗留进程/线程；系统时间变化不影响等待；连续截图 heap 稳定；新设备工具只新增 handler 并注册，不修改聊天循环；观察节点和截图不会跨模型步骤泄漏。

## 7. 实施波次与依赖

| 波次 | 计划内容 | 退出条件 | 预计人日 |
| --- | --- | --- | ---: |
| W0：基线 | R00；建立 runbook、性能和故障基线 | 所有核心指标可重复采集，Gradle 回环问题有稳定处理方式 | 1～2 |
| W1：止血 | R01、R02、R03、R04 | 写放大、无限流和图片峰值被控制；最终态可靠落盘 | 7～11 |
| W2：数据与 UI | R05、R06、R08 | 10k 历史查询/搜索达标；页面状态聚合；压缩算法达标 | 9～15 |
| W3：结构拆分 | R07、R09、R10、R12 | Service 和 Runtime 变为薄编排；单一生产执行主链；设备命令有界 | 11～18 |
| W4：收尾 | 全局代码复查、编译验证和文档清理；R11 排除 | 本轮指定范围完成，残留文件与旧引用清除 | 1～2 |

推荐依赖关系：

```text
R00 ─┬─> R01 ─┬─> R05 ─> R06 ─> R07
     │        └───────────────┐
     ├─> R02                  ├─> R09 ─> R10
     └─> R03 ─> R04           │
              └─> R08 ───────┘
                                  └─> R12

R11：按本轮用户要求排除，不纳入实施与验收结论
```

可并行项：

- R02 与 R03 可并行。
- R05 与 R08 可在接口冻结后并行。
- R06 的时间线索引与 R07 的通知控制器可由不同开发者并行，但 Service 状态迁移必须等待 `ChatUiState`/Runtime 状态边界稳定。
- R09 不应与 R01 同时大改 `ChatRuntime`，避免难以判断取消和持久化回归来源。

## 8. 测试矩阵

### 8.1 必须保留并重点回归

| 领域 | 现有测试 |
| --- | --- |
| 运行时、队列、取消、使用量 | `agent-core/.../ChatRuntimeTest.kt` |
| 上下文压缩和并发快照 | `agent-core/.../ContextManagerTest.kt` |
| SSE、断流、取消和错误脱敏 | `model/.../OpenAiChatGatewayTest.kt` |
| 数据库升级 | `data/.../ConversationMigrationTest.kt` |
| 生命周期和进行中回复 | `app/.../ChatInteractionTest.kt` |
| 多轮、草稿、附件、主题 | `app/.../ChatAcceptanceTest.kt` |
| 冷启动恢复 | `app/.../ChatColdStartTest.kt` |
| 真实压缩验收 | `app/.../ContextAcceptanceTest.kt` |
| 设备命令超时和进程清理 | `device/.../DeviceCommandsTest.kt` |

### 8.2 必须新增

| 测试 | 覆盖内容 |
| --- | --- |
| `ReplyCheckpointWriterTest` | 合并、定时、强制 flush、版本去重、失败重试、取消 |
| `DraftPersistenceCoordinatorTest` | debounce、latest-wins、切换/发送/退后台屏障 |
| `OpenAiChatGatewayLimitsTest` | 超大 SSE 行、累计正文/推理、工具参数/数量、首字节和空闲超时 |
| `ImageRequestBodyTest` | 流式 Base64/JSON、取消、预算边界、协议等价 |
| `ConversationQueryTest` | 专用 DAO、分页、FTS、中文和排序 |
| `ChatUiStateTest` | Flow 聚合、生命周期订阅、时间线关联索引 |
| `OverlayNotificationControllerTest` | meaningful distinct、节流、审批/完成立即更新 |
| `OverlayWindowControllerTest` | add/remove 幂等、旋转、边界、权限撤销 |
| `ChatRuntimeIsolationTest` | 跨会话 I/O 隔离、并发上限、清理超时 |
| `ContextPerformanceTest` | 长文本、密集工具历史、增量 token 估算 |

### 8.3 每波次门禁

1. 相关 JVM 单元测试全部通过。
2. 受影响模块的 lint/静态检查无新增问题。
3. Debug 构建通过；涉及 R8、资源或安全配置时 Release 构建也必须通过。
4. 涉及 Room schema 时必须从至少一个已发布版本数据库升级验证。
5. 涉及运行时、Service 或恢复语义时必须跑对应 instrumentation 场景。
6. 性能项提交前后使用同一 fixture 比较，未达到目标时说明原因，不能只以“代码更干净”验收。

## 9. 兼容、发布与回滚策略

- 每个任务独立提交/PR，不在同一变更中同时修改数据库 schema、模型协议和 UI 结构。
- R01 先以内部实现替换保持 Store 接口兼容；R05 再演进 DAO，降低同时跨三层的风险。
- 数据库只使用显式前向迁移，迁移前保留 fixture；禁止用清库掩盖问题。
- 临时 feature flag 必须写明负责人和删除波次，最多保留一个稳定发布周期。
- 新旧逻辑并存期间只允许单写；不要双写两个状态机后再尝试对账。
- 发布先内部/小范围验证：重点观察回复丢失、重复工具调用、通知频率、ANR、OOM、数据库迁移失败和网络超时。
- 若性能优化破坏最终态可靠性，优先回滚该优化；数据正确性优先于延迟。

## 10. 风险登记表

| 风险 | 概率 | 影响 | 预防/处理 |
| --- | --- | --- | --- |
| 检查点降频导致进程被杀时丢失最后少量文本 | 中 | 中 | 前后台/工具/终态强制 flush；缩短最大检查点间隔；明确“已确认检查点”语义 |
| mutex 拆分引入同会话重叠运行 | 中 | 高 | 每会话 actor/mutex；状态机性质测试；所有入口只通过 coordinator |
| SSE 超时误伤正常慢模型 | 中 | 中 | 区分首字节、空闲和总时长；空闲阈值可配置；有输出后不自动重放 |
| FTS 迁移耗时或索引敏感内容 | 中 | 高 | 分批回填、事务和进度；最小索引字段；隐私评审；迁移 fixture |
| Compose 聚合状态导致过度复制 | 中 | 中 | 使用稳定不可变结构和细粒度 selector；基准重组与分配 |
| Service 拆分期间窗口泄漏/重复通知 | 中 | 高 | 先提取可测 controller；add/remove 幂等；严格 lifecycle 测试 |
| 删除旧 Agent 路径破坏隐藏入口 | 低～中 | 高 | 构建引用、Manifest、运行入口、schema 和产品文档四重核对；先隔离后删除 |
| Android R8 开启后反射/序列化类被裁剪 | 中 | 高 | release smoke test、mapping 审查、最小 keep rule，不一次性激进压缩 |
| 本机 Gradle 回环故障掩盖真实回归 | 高 | 高 | W0 先固化可重复测试环境；未跑测试必须在变更说明中显式标记 |

## 11. 完成定义（Definition of Done）

单个重构任务只有同时满足以下条件才算完成：

- 行为契约已写入测试，正常、取消、错误、恢复至少各有一个场景。
- 相关性能指标有变更前后数据，达到目标或记录经评审的偏差原因。
- 没有新增无限队列、无限读取、无限等待或无界缓存。
- 日志和错误不包含 API Key、Bearer header、完整敏感响应或用户附件正文。
- 公共接口、数据库迁移和用户可见行为已更新文档。
- CodeGraph 受影响测试加人工补充测试均已评估；实际测试结果被记录。
- 临时兼容代码有明确删除条件；不存在无人负责的永久双路径。

## 12. 建议的首个迭代

首个迭代只做 R00、R01、R02 和 R03，不立即拆 `ChatRuntime` 或悬浮 Service。建议交付顺序：

1. 建立写次数、通知次数、长回复分配和 SSE 故障基线。
2. 注入共享 `OkHttpClient`，补齐 SSE 上限及首字节/空闲超时。
3. 引入 `ReplyCheckpointWriter`，保留 Store 外部契约，验证所有终态强制 flush。
4. 将草稿保存改为 latest-wins + debounce + 屏障。
5. 运行核心单元测试、冷启动/取消/恢复 instrumentation 和前后基准。

该迭代预计 7～11 人日，能够先消除最明显的写放大和网络失控风险，同时为后续 UI、数据库和服务拆分建立稳定接口。
