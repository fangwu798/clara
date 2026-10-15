# clara-application 服务 — 技术设计文档

> 状态：已审核
> 前置：`clara-application-service-business-design.md`（已审核）、`clara-orchestration-service-technical-design.md`（已审核）、`clara-core-contract-technical-design.md`（已审核）
>
> 修订（2026-10-15）：CLI 运行模式已删除——`com.clara.adapter.cli` 下的 ClaraCli / CliArgumentsParser / CliOptions / CliExitCodes / NdjsonProtocolWriter 及其契约测试一并移除。原因：`clara.jar` 的入口始终是交互式对话 `ClaraApplication`（shade 插件的 mainClass），CLI 没有任何生产调用方，任务改由工作流（`run_workflow` / `publish_workflow`）承担。下文 §4.3、§5.3 等章节保留为当时的设计记录。
> 修订（2026-10-15）：工作区运行入口已删除——`com.clara.workspace`（WorkspaceBusinessRunner / DefaultWorkspaceBusinessRunner / WorkspaceRunRequest / WorkspaceResumeRequest / WorkspaceRunResult / WorkspacePolicy）与 core 的 `com.clara.enums.cli`、`com.clara.enums.workspace` 一并移除，`ApplicationBootstrap` 不再装配该入口、也不再有 `workspaceRunner()` 访问器。原因：CLI 删除后无任何调用方。下文 §4 / §5 中相关章节保留为当时的设计记录。
> 修订（2026-10-15）：MCP 工具下发补齐——新增 `com.clara.tool.McpToolCatalog`（把已发现的 MCP 工具声明成可下发给模型的工具）与 `com.clara.tool.CompositeToolCatalog`（本地技能工具与外部 MCP 工具合并，同名本地优先），`SkillMatchCoordinator` 改用合并目录，使「发现 → 声明 → 调用」三环闭合；同时启动时由 `loadMcpServers` 完成「注册 → 发现 → 登记」，并把结果写进启动横幅。

## 1. 目标

作为宿主应用直接使用的程序入口，组合 core / model / memory / skill / workflow / mcp / orchestration 各模块能力，提供 CLI 运行模式（现阶段），REST / SSE 服务端模式后续阶段支持。

application 只做**入口与组合**（装配、参数解析、运行结果呈现），不实现任何领域能力。除 clara-model 与 clara-core 外，现有 application 代码按新业务重写、不做兼容；现有代码中的 `tenantId` 按无租户模型去除。

## 2. 模块依赖

- 依赖全部模块：`clara-core`、`clara-model`、`clara-memory`、`clara-skill`、`clara-workflow`、`clara-mcp`、`clara-orchestration`；
- 依赖方向：application 是最上层，不被任何模块依赖；装配 `DefaultConversationOrchestrator` 及各模块实现并注入。

## 3. 文件结构（要创建 / 保留的文件）

```
clara-application/src/main/java/com/clara/
├── ApplicationBootstrap.java              # 程序装配入口（组合各模块，新增）
├── ClaraApplication.java                  # 最小可运行入口（装配自检，新增）
├── adapter/
│   ├── cli/
│   │   ├── ClaraCli.java                  # CLI 运行模式入口（改造）
│   │   ├── CliArgumentsParser.java        # 命令参数解析（保留）
│   │   ├── CliCommand.java                # 命令枚举（保留）
│   │   ├── CliExitCodes.java              # 退出码（保留）
│   │   ├── CliOptions.java                # 选项载体（保留）
│   │   ├── CliOutputFormat.java           # 输出格式（保留）
│   │   ├── CliUiMode.java                 # UI 模式（保留）
│   │   └── NdjsonProtocolWriter.java      # 结构化输出（json / json-stream）（保留）
│   ├── rest/
│   │   ├── WorkspaceRunController.java    # REST 控制接口（后续阶段）
│   │   ├── InteractionController.java     # 交互控制接口（后续阶段）
│   │   ├── WorkspaceRunSseAdapter.java    # SSE 事件流适配（后续阶段）
│   │   ├── SseEvent.java / RestResponse.java / RestErrors.java  # 协议载体（保留）
│   └── document/                          # 文档解析适配（保留）
├── tool/                                  # 工具接入与下发（新增）
│   ├── ExternalToolBinding.java           # 外部工具路由绑定值对象（新增）
│   ├── ExternalToolCatalog.java           # 工具名 → 外部工具路由登记表（新增）
│   ├── CompositeToolInvoker.java          # 本地 skill 优先、外部 mcp 兜底的组合工具调用器（新增）
│   ├── McpToolCatalog.java                # 把已发现的 MCP 工具声明为可下发的工具声明（新增）
│   └── CompositeToolCatalog.java          # 本地技能工具 + 外部 MCP 工具合并（同名本地优先，新增）
├── workspace/                             # 应用层组合能力（保留）
│   ├── application/WorkspaceBusinessRunner.java  # 核心业务入口（run / resume / cancel）
│   └── ...
├── media/                                 # 媒体应用层能力（保留）
└── deployment/local/                      # 部署策略（保留）
```

## 4. 实现类清单（每个类含作用 / 使用范围 / 为什么需要 / 业务细节 / 技术细节）

### 4.1 装配入口

**`ApplicationBootstrap`（`com.clara`，新增）**
- 作用：程序装配入口，按配置构造各模块实现（memory / skill / model / workflow / mcp / orchestration）并组合成可运行的应用。
- 使用范围：进程启动时调用一次。
- 为什么需要：宿主应用的唯一装配点，把「接口契约」与「具体实现」在启动期绑定，避免运行时散落 `new`。
- 业务细节：装配 model 的统一模型调用入口与 token 估算器；装配 memory 的窗口记忆工厂（按「会话 + 供应商 + 模型」隔离）、会话存储、语义记忆存储与压缩编排；装配 skill 的技能注册表（内置 + 运行期目录，描述向量化写入语义记忆）、提示词与工具调用；装配 workflow 引擎；最终组装 `DefaultConversationOrchestrator`。未配置的可选实现以 null 注入，编排层降级（不记录、不影响调用）。
- 技术细节：实现 `Closeable`；提供无参构造（默认 `HashingEmbeddingClient(1024)` + 维度 1024）与 `ApplicationBootstrap(IEmbeddingClient, int embeddingDimension)`（便于接入真实凭据）；`IStreamModel = CompatibleStream(Jdk8HttpTransport)`、`ITokenCountEstimator = TiktokenTokenCountEstimator(DEFAULT_ENCODING)`、`ISemanticMemoryStore = LocalSemanticMemoryStore(dimension)`、`ConversationStore = InMemoryConversationStore`、`SkillRegistry.reload()`、`IWorkflowEngine = DefaultWorkflowEngine(InMemoryCheckpointStore, 4)`；`WindowMemoryFactory` 在 provider 为 null 时以 `"default"` 占位（`DefaultWindowMemory` 要求 provider 非空）；对外暴露 `orchestrator()` / `workflowEngine()` / `embeddingClient()`；`close()` 依次释放工作流引擎、语义记忆存储、会话存储（对 `throws IOException` 的 close 内部静默处理）；构造本身不访问网络。

**`ClaraApplication`（`com.clara`，新增）**
- 作用：最小可运行入口，构造 `ApplicationBootstrap` 并打印装配结果，作为宿主启动参考。
- 使用范围：进程启动 / 装配自检。
- 为什么需要：提供零依赖的可运行入口，验证装配链路（含 jtokkit 加载、技能目录扫描）无异常。
- 业务细节：`main` 中装配并打印可用能力（orchestrator / workflowEngine / embeddingClient 的实现类名），装配失败打印错误并以非零码退出；不发起模型调用（无外部凭据时装配仅到构造阶段）。
- 技术细节：`public static void main(String[] args)`，`try/catch(RuntimeException)` 打印失败信息并 `System.exit(1)`，`finally` 中 `bootstrap.close()` 释放资源。

### 4.2 CLI 运行模式

**`ClaraCli`（`com.clara.adapter.cli`，改造）**
- 作用：命令行交互入口，解析参数、调用核心业务入口、按输出格式呈现结果。
- 使用范围：CLI 运行模式。
- 为什么需要：现阶段唯一的运行模式入口；解析与呈现与业务逻辑解耦。
- 业务细节：`run` 命令构造 `WorkspaceRunRequest` 调 `runner.run`；`resume` 命令从答案文件读取（`expectedInstanceVersion` / `idempotencyKey` / `answer`）构造 `WorkspaceResumeRequest` 调 `runner.resume`；按输出格式呈现（plain 打印状态与待输入问题、json / json-detached 结构化输出、json-stream 走 NDJSON 协议）；json-stream 下支持 `run.cancel` 与 `interaction.response` 命令、以 `interaction.required` / `run.completed` / `error` 事件推送；以 `CliExitCodes` 返回退出码。
- 技术细节：``int execute(String[] args, InputStream, PrintStream out, PrintStream error)`；构造注入 `WorkspaceBusinessRunner` + `ObjectMapper`；`MAX_LINE_BYTES = 1048576` 限制单行；`render` / `stream` / `emit` / `writeError` 私有方法；异常分类处理（`ClaraException` → 错误事件与退出码、`IllegalArgumentException` → `INVALID`、`IOException` → `INVALID`）。

**`CliArgumentsParser` / `CliCommand` / `CliExitCodes` / `CliOptions` / `CliOutputFormat` / `CliUiMode` / `NdjsonProtocolWriter`（`com.clara.adapter.cli`，保留）**
- 作用：命令、目标、写模式、UI 模式、输出格式的参数解析与结构化输出。
- 使用范围：CLI 入口内部调用。
- 为什么需要：把参数解析与输出协议细节从入口逻辑中隔离，支持 plain / json / json-detached / json-stream / tui 等模式。
- 业务细节：`parse` 识别 `run` / `resume` 命令，校验选项白名单（`--workspace` / `--goal` / `--skill` / `--write-mode` / `--ui` / `--output` / `--config` / `--run-id` / `--interaction-id` / `--answer-file` 等），提供默认值（`write-mode=preview`、`ui=auto`、`output=text`）；`--approve-changes` 要求 `apply` 写模式；`resume` 要求 `run-id` + `interaction-id` + 答案文件。
- 技术细节：`CliOptions` 为不可变值对象（含 `command` / `workspace` / `answerFile` / `config` / `user` / `runId` / `goal` / `skill` / `interactionId` / `mode` / `ui` / `output` / `approve` / `noColor`）；`parse` 用 `Map`+`Set` 收集选项、路径经 `toRealPath` 规范化并校验文件 / 目录类型（不存在则报错）；枚举解析失败抛 `IllegalArgumentException`；`NdjsonProtocolWriter` 按行输出带类型的结构化事件并限制单行字节数。

### 4.3 核心业务入口

**`WorkspaceBusinessRunner`（`com.clara.workspace.application`，保留）**
- 作用：核心业务入口，提供 `run` / `resume` / `cancel`，是各运行模式共用的业务入口。
- 使用范围：CLI（及后续 REST / SSE）模式共同调用。
- 为什么需要：让不同运行模式复用同一业务入口，避免每种模式各写一套流程。
- 业务细节：`run` 按请求执行一次业务运行并返回结果（可能停在等待人工输入）；`resume` 用人工反馈续跑（携带期望实例版本与幂等键）；`cancel` 终止正在运行的实例。
- 技术细节：接口方法 `WorkspaceRunResult run(WorkspaceRunRequest)`、`WorkspaceRunResult resume(WorkspaceResumeRequest)`、`void cancel(String runId, String reason)`；实现由本模块提供（把运行落到装配的编排 / 工作流能力），并遵循无租户模型。

**`WorkspaceRunRequest` / `WorkspaceResumeRequest` / `WorkspaceRunResult` / `WorkspaceRunStatus`（`com.clara.workspace.application`，保留）**
- 作用：运行 / 恢复请求与运行结果的载体。
- 使用范围：核心业务入口的入参与返回。
- 为什么需要：统一运行入参与结果结构，屏蔽调用细节。
- 业务细节：请求承载工作区、目标、写模式、审批与输入等；结果承载运行标识、状态、实例版本、输出与待处理交互（`WAITING_INPUT` 状态必须携带交互）。
- 技术细节：均不可变值对象 + 构造期校验；`WorkspaceRunRequest` 含 `userId` / `runId` / `goal` / `skillId` / `workspace`(绝对路径) / `accessMode` / `approveChanges` / `inputs`；`WorkspaceRunResult` 含 `runId` / `status` / `instanceVersion` / `outputs` / `interactions`(`List<InteractionRequest>`)，`WAITING_INPUT` 时 `interactions` 不可为空；`WorkspaceRunStatus` 为枚举（成功 / 等待输入 / 失败）。**按无租户模型去除原 `tenantId` 字段。**

### 4.4 适配层（协议边界）

**`WorkspaceRunController` / `InteractionController` / `WorkspaceRunSseAdapter`（`com.clara.adapter.rest`，后续阶段）**
- 作用：把运行 / 恢复 / 取消与交互能力暴露为 HTTP 语义与带序号的 SSE 事件流。
- 使用范围：REST / SSE 服务端模式。
- 为什么需要：协议适配与业务解耦，宿主应用负责映射到实际 HTTP 路由。
- 业务细节：控制器提供运行 / 恢复 / 取消 / 交互控制端点；SSE 适配器把运行结果与错误转换为带序号事件（`interaction.required` / `run.completed` / `error`）。
- 技术细节：依赖 core 契约（`IInteractionService` / `IInteractionResumeCoordinator` 等）与 `com.clara.dto.interaction.*`；与 CLI 共用 `WorkspaceBusinessRunner`，不重复业务逻辑。

**`adapter/document/*`、`media/*`、`deployment/local/*`（保留）**
- 作用：文档解析、媒体应用层能力与部署策略等既有应用层能力。
- 使用范围：被业务运行器 / 装配调用。
- 为什么需要：应用层组合能力，非本次新业务重点，按现状保留。
- 业务细节：文档解析支持 PDF / Word / Excel / 文本；媒体能力覆盖文件 / 对象存储、任务调度与回调校验；部署策略提供单机存储策略。
- 技术细节：按现有实现保留，不改变其内部逻辑。

### 4.5 外部工具接入（MCP）

**`CompositeToolInvoker`（`com.clara.tool`，新增）**
- 作用：实现 core 契约 `IToolInvoker` 的组合工具调用器，在本地技能工具与外部 MCP 工具之间按工具名分派，是工具往返（`ToolRoundTripCoordinator`）的统一执行入口。
- 使用范围：由 `ApplicationBootstrap` 装配后交给 orchestration 的工具往返协调器，替代原先直接注入的 skill `DefaultToolInvoker`。
- 为什么需要：模型返回的工具名可能既包含本地技能，也包含外部 MCP 工具；需要一个判定点决定走本地脚本还是走 clara-mcp，避免 orchestration 感知工具来源。
- 业务细节：**本地优先、外部兜底**——`SkillRegistry.findToolByName(name)` 命中即委托本地 `IToolInvoker`（skill 的 `DefaultToolInvoker`）执行；否则查 `ExternalToolCatalog.find(name)`，命中则用当前 `ConvContext.current()` / `currentCall()` 与绑定信息构造 `McpToolCall` 调 `McpGateway.callTool`，把 `McpToolResult` 内容序列化为 JSON 文本作为 `ToolResult.output`（`callId` 沿用 `IToolInvocation.callId()`）；两处都未命中则抛领域错误，指明本地技能未注册且外部工具未登记。降级：外部工具目录为空时非本地工具一律按「未找到」处理；`mcpGateway` 为 null 时外部分支抛「未接入」错误。
- 技术细节：构造注入 `SkillRegistry` + 本地 `IToolInvoker` + `McpGateway` + `ExternalToolCatalog`（除 `mcpGateway` 可空外均要求非 null）；工具名空白抛 `INVALID_ARGUMENT`，未命中抛 `SKILL_NOT_FOUND`，网关缺失抛 `CAPABILITY_NOT_SUPPORTED`，缺少会话 / 调用上下文或入参非法 JSON 抛 `INVALID_ARGUMENT`；入参 JSON 字符串经 `ObjectMapper` 解析为参数 map（空白视为空 map）。

**`ExternalToolCatalog`（`com.clara.tool`，新增）**
- 作用：外部工具路由登记表，维护「工具名 → `ExternalToolBinding`」映射，是「名字是不是外部工具、走哪个 MCP 服务端」的唯一判定与查询点。
- 使用范围：由 `ApplicationBootstrap` 创建并持有；宿主 / 能力发现流程登记外部工具，`CompositeToolInvoker` 查询路由。
- 为什么需要：本地工具由 skill 注册表判定，外部工具需另有一处登记；把登记与查询收敛到一个线程安全目录，避免在调用链路里硬编码服务端信息。
- 业务细节：`register(toolName, binding)` 登记（同名覆盖）、`find(toolName)` 查找（未命中返回 null）、`clear()` 清空、`snapshot()` 返回当次不可变副本供观测 / 审计。
- 技术细节：内部用 `ConcurrentHashMap` 存储，登记 / 查找 / 清空可并发；`register` 校验工具名非空白、绑定非 null；`snapshot()` 返回 `LinkedHashMap` 的不可修改副本。

**`ExternalToolBinding`（`com.clara.tool`，新增）**
- 作用：外部工具路由绑定值对象，承载 `tenantId` / `serverKey` / `snapshotId` / `schemaHash`，用于在调用时构造 `McpToolCall`。
- 使用范围：与 `ExternalToolCatalog` 配套，由登记方创建、`CompositeToolInvoker` 读取。
- 为什么需要：把「一次外部工具调用所需的 MCP 定位信息」打包为一个不可变值对象，避免散落的多个参数。
- 技术细节：构造后不可变、可跨线程共享；任一字段为 null 或空白时构造抛 `IllegalArgumentException`。

**`McpExternalToolRegistrar`（`com.clara.tool`，新增）**
- 作用：把一次 MCP 能力快照（`McpServerSnapshot`）中的工具登记为外部工具路由绑定，衔接「MCP 发现」与「`ExternalToolCatalog` 登记」。
- 使用范围：由 `ApplicationBootstrap` 持有，在 `loadMcpServers` 流程发现成功后调用；也可由宿主对任意快照直接调用。
- 为什么需要：发现只产出能力快照，工具名要进入外部工具路由目录才能被 `CompositeToolInvoker` 命中；把「快照 → 绑定」的转换收敛到一处，避免装配层散落遍历逻辑。
- 业务细节：`register(tenantId, snapshot, catalog)` 遍历快照工具，对每个工具登记 `ExternalToolBinding(tenantId, snapshot.getServerKey(), snapshot.getSnapshotId(), tool.getSchemaHash())`（同名工具按目录约定以最后一次为准），返回登记的工具个数。
- 技术细节：无状态、可跨线程共享；`tenantId` 空白、`snapshot` / `catalog` 为 null 时抛 `IllegalArgumentException`；不持有 MCP 客户端、不发起调用。

**`McpToolCatalog`（`com.clara.tool`，新增）**
- 作用：core 契约 `IToolCatalog` 的实现，把「已发现并已登记」的 MCP 工具声明成可下发给模型的工具。
- 使用范围：由 `ApplicationBootstrap` 装配进 `CompositeToolCatalog`，供编排层组装请求体时取「本轮可下发工具」。
- 为什么需要：调用侧路由早已能按工具名转给 MCP 网关，但下发侧原先只声明技能注册表里的本地工具，模型永远不知道该调用哪个外部工具；补上本目录后「发现 → 声明 → 调用」才闭合。
- 业务细节：只列注册状态可用的服务端（`McpServerRegistry.listEnabled(tenantId)`）的最新快照，把工具名 / 描述 / 入参 Schema 转成 `ToolContext(ToolSpecification)`；入参 Schema 直接沿用 MCP 声明的 JSON Schema，不额外包装。
- 技术细节：`McpServerRegistry` 与 `McpCapabilityCatalog` 都可被后续发现流程更新，故每次调用都重新读取快照、不缓存；实例无状态可跨线程共享；构造要求 `tenantId` 非空白。

**`CompositeToolCatalog`（`com.clara.tool`，新增）**
- 作用：把本地技能工具与外部 MCP 工具合成一份下发声明，同名时本地优先。
- 使用范围：装配在编排层取「本轮可下发工具」的位置上（`SkillMatchCoordinator`）。
- 为什么需要：调用侧 `CompositeToolInvoker` 的路由顺序就是「技能注册表命中即走本地，否则查外部目录」；若下发声明里同名工具以外部为准，模型会以为在调 MCP 工具、实际却由本地技能执行，两边 Schema 一旦不同就会出现难查的参数错误。
- 业务细节：按「先主后次」收集并去重（先收集者优先）；目录为 null、`availableTools()` 返回 null、条目为 null、缺函数签名的声明一律跳过，不抛异常。
- 技术细节：返回不可修改列表；用 `LinkedHashMap` 保持稳定顺序。

## 5. 关键实现细节

### 5.1 装配（`ApplicationBootstrap`）

- 读取运行配置；按「供应商 + 模型」隔离的窗口记忆由工厂按需创建（默认实现 `DefaultWindowMemory(conversationId, provider, model, conversationStore)`）；
- model 装配：`CompatibleStream(Jdk8HttpTransport)` 提供统一模型调用入口，`TiktokenTokenCountEstimator` 提供 token 估算；
- memory 装配：`LocalSemanticMemoryStore(dimension)`（本地语义记忆）、`InMemoryConversationStore`（会话存储）、`DefaultSummarizeCoordinator(streamModel, promptProvider)`（压缩编排）；
- skill 装配：`SkillRegistry`（内置 `resource/config` + 运行期 `config/skill`，`reload()` 扫描并写入技能描述向量）、`DefaultPromptProvider`、`DefaultToolInvoker(DefaultToolExecutor)`；下发给模型的工具声明取合并目录 `CompositeToolCatalog(RegistryToolCatalog(技能注册表), McpToolCatalog(mcpServerRegistry, mcpCapabilityCatalog, DEFAULT_MCP_TENANT))`；
- mcp 装配：`InMemoryMcpServerRegistry` + `InMemoryMcpCapabilityCatalog` + `DefaultMcpClientFactory` → `DefaultMcpGateway`，并创建 `ExternalToolCatalog`（空，供宿主 / 发现流程登记）；工具往返以 `CompositeToolInvoker(技能注册表, DefaultToolInvoker, mcpGateway, externalToolCatalog)` 在本地技能与外部工具间分派；
- mcp 接入：`loadMcpServers(ApplicationBootstrap.DEFAULT_MCP_TENANT)` 在启动时读 `config/mcp` 完成「注册 → 握手发现 → 登记外部工具」，横幅打印 `mcp : N 个服务端 / M 个外部工具`；单个服务端发现失败只跳过该服务端，不阻断启动与对话。stdio 允许清单由 `allowedStdioExecutables()` 现扫配置（叠加环境变量 `CLARA_MCP_ALLOWED_EXECUTABLES` 与 `node` 兜底）并按 PATH / PATHEXT 解析成绝对路径，避免每接一个 stdio 服务端都要改代码；
- workflow 装配：`DefaultWorkflowEngine(InMemoryCheckpointStore, 并行度)`；
- orchestration 装配：`ContextAssembler(压缩, 找回, 技能)`、`CapabilityDispatcher(streamModel)`、`ToolRoundTripCoordinator(compositeToolInvoker)`、`CallRecordPublisher(null)`、`WindowMemoryFactory`，最终组装 `DefaultConversationOrchestrator`；
- 可选实现（`ICallRecordSink` / `IWorkflowDefinitionResolver`）以 null 注入，编排层自行降级；
- 对外暴露 `orchestrator()` / `workspaceRunner()` / `workflowEngine()` / `embeddingClient()` / `mcpGateway()` / `externalToolCatalog()`；
- 语义记忆维度须与 `IEmbeddingClient` 输出维度一致（默认 1024）。

### 5.2 最小运行入口（`ClaraApplication`）

1. 构造 `ApplicationBootstrap`；
2. 打印装配成功信息与三类能力的实现类名；
3. 装配异常时打印错误并 `System.exit(1)`；
4. `finally` 中 `close()` 释放资源。

### 5.3 CLI 运行模式（`ClaraCli`）

1. `CliArgumentsParser` 解析命令行参数（命令 / 工作区 / 目标 / 写模式 / UI 模式 / 输出格式）；
2. 按命令调用 `WorkspaceBusinessRunner` 的 `run` / `resume`（`resume` 从答案文件读取版本与答案）；
3. 按 `CliOutputFormat` / `CliUiMode` 呈现结果：plain 直接输出、json / json-detached 结构化输出、json-stream 经 `NdjsonProtocolWriter` 推送事件、tui 交互式展示；
4. 以 `CliExitCodes` 返回进程退出码（成功 / 等待输入 / 失败 / 参数非法）。

### 5.4 REST / SSE（后续阶段）

- `WorkspaceRunController` / `InteractionController` 暴露运行 / 恢复 / 取消 / 交互控制接口；
- `WorkspaceRunSseAdapter` 把运行结果与错误转换为带序号的 SSE 事件，供流式服务端推送；
- 与 CLI 共用 `WorkspaceBusinessRunner`，不重复业务逻辑。

### 5.5 外部工具接入

1. **装配**：`ApplicationBootstrap` 以 `InMemoryMcpServerRegistry` + `InMemoryMcpCapabilityCatalog` + `DefaultMcpClientFactory` 组装 `DefaultMcpGateway` 与 `DefaultMcpDiscoveryService`（支持协议版本 `2024-11-05`、单类能力最大分页 16），并创建 `ExternalToolCatalog` 与 `McpExternalToolRegistrar`；工具往返由 `CompositeToolInvoker` 承担（本地 skill 优先、外部 mcp 兜底）。**构造阶段不访问网络**：MCP 服务端连接推迟到显式调用 `loadMcpServers`。
2. **配置 → 注册 → 发现 → 登记（`loadMcpServers`）**：`loadMcpServers(tenantId, configDirectory)`（便捷重载 `loadMcpServers(tenantId)` 用默认目录 `config/mcp`）依次完成：
   - a. `McpServerConfigDirectoryLoader(new McpServerConfigLoader(), configDirectory).load()` 递归加载并合并 `*.json` 配置；
   - b. `McpServerRegistrar(registry).registerAll(tenantId, configs)` 把配置转为注册项（状态 `ACTIVE`）登记进 `McpServerRegistry`；
   - c. 对每个已注册服务端调用 `DefaultMcpDiscoveryService.refresh(tenantId, serverKey)` 完成发现（发布快照 + 按结果更新注册状态）；
   - d. 用 `McpCapabilityCatalog.latest(tenantId, serverKey)` 取回快照，交 `McpExternalToolRegistrar.register(tenantId, snapshot, catalog)` 把其工具登记进 `ExternalToolCatalog`；
   - e. 返回成功发现的服务端数。**失败隔离**：单个服务端的发现失败（协议不兼容 / 身份变化 / 连接异常）被捕获并跳过，不影响其余服务端，也不阻断返回；配置加载失败属装配期错误，直接抛出。
3. **路由登记**：`ExternalToolCatalog` 维护「工具名 → `ExternalToolBinding`（tenantId / serverKey / snapshotId / schemaHash）」。装配层通过 `loadMcpServers` 自动登记；宿主也可对发现后的快照手动调用 `McpExternalToolRegistrar.register` 或直接 `ExternalToolCatalog.register`。
4. **调用分派**：工具往返时 `CompositeToolInvoker.invoke` 先查 `SkillRegistry.findToolByName`，命中委托本地 `IToolInvoker`；否则查 `ExternalToolCatalog.find`，命中则用当前 `ConvContext.current()` / `currentCall()` 与绑定构造 `McpToolCall` 交 `McpGateway.callTool`，把 `McpToolResult` 内容转为 JSON 文本作为 `ToolResult.output`；两处均未命中抛领域错误（本地技能未注册且外部工具未登记）。
5. **降级**：`mcpGateway` 为 null 时外部分支抛「未接入」错误；目录为空时非本地工具按「未找到」处理。

## 6. 业务场景与功能映射

### 6.1 业务职责

作为宿主应用直接使用的程序入口：装配各模块能力（`ApplicationBootstrap`），提供 CLI 运行模式（`ClaraCli`），并以核心业务入口（`WorkspaceBusinessRunner`）统一 `run` / `resume` / `cancel`，供不同运行模式复用。application 只做入口与组合，不实现领域能力。

### 6.2 典型业务场景（业务流程 → 技术落点）

**场景一：装配自检 / 启动**
进程启动 → 构造 `ApplicationBootstrap` 装配各模块 → 校验装配成功 → 释放资源。落点：`ApplicationBootstrap` / `ClaraApplication`。

**场景二：CLI 一次运行（run）**
`run --workspace ... --goal ...` → 解析参数 → 构造 `WorkspaceRunRequest` → `runner.run` → 按输出格式呈现结果 → 返回退出码。落点：`ClaraCli` + `CliArgumentsParser` + `WorkspaceBusinessRunner`。

**场景三：CLI 交互恢复（resume）**
实例等待人工输入 → 用户提交答案文件 → 读取版本 / 幂等键 / 答案 → 构造 `WorkspaceResumeRequest` → `runner.resume` → 呈现结果。落点：`ClaraCli` + `WorkspaceBusinessRunner`。

**场景四：json-stream 交互**
`--ui json-stream` → 推送 `interaction.required` → 读取 stdin 命令（`interaction.response` / `run.cancel`）→ ack 并继续推送 `run.completed`。落点：`ClaraCli.stream` + `NdjsonProtocolWriter`。

**场景五：REST / SSE（后续阶段）**
服务端映射 HTTP 路由 → 控制器调用 `WorkspaceBusinessRunner` → SSE 适配器推送事件流。落点：`adapter/rest/*`。

### 6.3 功能需求映射（业务 FR → 技术实现）

| 业务需求 | 实现落点 |
| --- | --- |
| FR-A1 程序入口 | `ApplicationBootstrap` + `ClaraApplication` |
| FR-A2 CLI 运行模式 | `ClaraCli` + `CliArgumentsParser` + `CliOptions` / `CliOutputFormat` / `CliUiMode` / `NdjsonProtocolWriter` |
| FR-A3 REST 模式（后续） | `WorkspaceRunController` / `InteractionController` |
| FR-A4 SSE 模式（后续） | `WorkspaceRunSseAdapter` |
| FR-A5 应用层组合 | `adapter/document/*`、`media/*`、`deployment/local/*` |

### 6.4 业务规则与决策的落点

| 业务规则 / 决策 | 落点 |
| --- | --- |
| AR1 application 是宿主应用直接使用的程序入口 | `ApplicationBootstrap` / `ClaraApplication` |
| AR2 核心业务入口为 `WorkspaceBusinessRunner`（run / resume / cancel） | `WorkspaceBusinessRunner` 接口 + 本模块实现 |
| AR3 CLI / REST / SSE 共用同一业务入口（现阶段仅 CLI） | 各运行模式均调用 `WorkspaceBusinessRunner` |
| AR4 应用层不依赖租户，去除现有 `tenantId` | `WorkspaceRunRequest` / `WorkspaceResumeRequest` / `CliOptions` 等按无租户改造 |
| AD1 现阶段仅 CLI，REST / SSE 后续 | `ClaraCli` 为唯一已实现运行模式 |
| AD2 仅 CLI 模式实现 TUI | `CliUiMode.TUI` |

## 7. 非目标

- 不实现核心领域能力（模型 / 记忆 / 技能 / 编排 / 工作流均由对应模块实现）；
- 不实现 Web 容器（REST / SSE 仅是协议适配边界，宿主负责映射实际 HTTP 路由）；
- 现阶段不实现 REST / SSE 运行模式（后续阶段支持），仅实现 CLI；
- 不涉及观测日志系统（脱敏 / 截断 / SLF4J 等）。
