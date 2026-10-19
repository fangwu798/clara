# clara-orchestration 服务 — 技术设计文档

> 状态：已审核
> 前置：`clara-orchestration-service-business-design.md`（已审核）、`conversation-context-call-record-business-design.md`（已审核）、`clara-core-contract-technical-design.md`（已审核）、`clara-memory-service-technical-design.md`（已审核）、`clara-skill-service-technical-design.md`（已审核）、`clara-workflow-service-technical-design.md`（已审核）

## 1. 目标

对话编排（Agent Loop）：把 core / model / memory / skill / workflow / mcp 组合成完整对话流程，串起「会话历史恢复 → 窗口限制 → 超限压缩 → 技能匹配检索 → 对话知识找回 → 组装请求 → 模型调用 → 调用记录 → 工具往返 / 多模态能力调用」的闭环，覆盖文本、图片、视频、音频。

本模块只做**组合与编排**，不实现任何具体能力（模型、记忆、技能、工作流、mcp 均由对应模块实现），全部通过 core 定义的接口调用。现有 orchestration 代码仅作参考、按新业务重写；其中旧的 `interaction` / `planner`（已迁入 clara-workflow）代码一并清理。

命名规范与包结构约束以 `clara-core-contract-technical-design.md` 为准：core 契约 / DTO / 枚举已在 core 定义，本模块只提供 `com.clara.orchestration.*` 下的实现类；编排态 DTO（如一次对话请求 / 结果）属编排层自有类型，放本模块 `com.clara.orchestration.api`，遵循 Lombok 注解约定。

## 2. 模块依赖

- `clara-core`：消费 `IWindowMemory`、`ISummarizeCoordinator`、`ISemanticMemoryStore`、`IEmbeddingClient`、`ITokenCountEstimator`、`IPromptProvider`、`IToolInvoker`、`ICallRecordSink`、`IWorkflowEngine` 等契约与 DTO / 枚举；
- `clara-model`：模型调用入口（`IStreamModel`）、会话上下文（`ConvContext`）、消息类型（`IContent` 及各 delta 实现）、`IEmbeddingClient` / `ITokenCountEstimator` 实现；
- `clara-memory`：窗口执行、压缩编排、语义记忆（写入 / 检索 / 删除）、会话持久化实现；
- `clara-skill`：提示词获取、工具方法调用与脚本执行实现；
- `clara-workflow`：将工作流作为一个步骤调用；
- `clara-mcp`：工具往返中的外部服务联动。

依赖方向：`clara-orchestration` → 各能力模块（上层组合）；各能力模块不反向依赖 orchestration。`clara-application` 依赖本模块完成宿主装配。

## 3. 文件结构（要创建的文件）

```
clara-orchestration/src/main/java/com/clara/orchestration/
├── DefaultConversationOrchestrator.java   # Agent Loop 主入口（编排全链路）
├── OrchestrationErrors.java               # 编排领域错误
├── api/
│   ├── ConversationRequest.java           # 一次对话请求（会话标识、用户输入、能力种类、control 配置）
│   ├── ConversationResult.java            # 一次对话结果（回复、状态、工具往返次数、用量）
│   └── OrchestrationStatusEnum.java       # 编排终态（完成 / 取消 / 失败）
├── context/
│   ├── ContextAssembler.java              # 装配本轮请求消息（历史 / 窗口 / 压缩 / 检索注入 / 技能装配）
│   ├── WindowCompressionCoordinator.java  # 窗口超限时触发压缩并回填摘要
│   └── SemanticRecallCoordinator.java     # 对话知识找回（被淘汰消息的向量检索注入）
├── skill/
│   └── SkillMatchCoordinator.java         # 技能匹配（描述向量检索 → 提示词与工具方法装配）
├── tool/
│   └── ToolRoundTripCoordinator.java      # 工具往返（检测 tool_calls → 调用 → 回填 → 循环）
├── capability/
│   └── CapabilityDispatcher.java          # 多模态能力分派（CHAT / IMAGE / VIDEO / AUDIO）
├── record/
│   └── CallRecordPublisher.java           # 响应结束产生调用记录并交给接收方
└── workflow/
    └── WorkflowStepInvoker.java           # 把工作流作为编排中的一个步骤调用
```

## 4. 实现类清单（每个类含作用 / 使用范围 / 为什么需要 / 业务细节）

### 4.1 编排入口

**`DefaultConversationOrchestrator`（`com.clara.orchestration`）**
- 作用：对话编排主入口，驱动完整 Agent Loop（组装 → 模型调用 → 记录 → 工具/能力往返循环）。
- 使用范围：宿主（clara-application）与适配层发起一次对话时；持有各编排协作者。
- 为什么需要：编排是 orchestration 的唯一核心职责，需要单一入口把各环节按序串起。
- 业务细节：以 `ConversationRequest` 为输入，按「会话 + 供应商 + 模型」取窗口记忆实例恢复历史；循环执行「`ContextAssembler` 装配 → `CapabilityDispatcher` 调用 → `CallRecordPublisher` 记录 → 追加助手回复 → 若有工具调用则 `ToolRoundTripCoordinator` 往返」；循环以「无工具调用」为终止条件、并有往返次数上限保护；成功返回 `COMPLETED`、命中取消返回 `CANCELLED`、异常返回 `FAILED`；全过程绑定 `ConvContext`，结束时解绑。
- 技术细节：构造器注入 6 个依赖（`WindowMemoryFactory`、`ContextAssembler`、`CapabilityDispatcher`、`ToolRoundTripCoordinator`、`CallRecordPublisher`、`ITokenCountEstimator`），前 5 个非空校验；对外仅 `ConversationResult converse(ConversationRequest)`，无状态、可复用；因 `IWindowMemory` 无工厂契约，定义内嵌 `@FunctionalInterface WindowMemoryFactory`（`create(conversationId, provider, model)`）；工具往返默认上限常量 `DEFAULT_MAX_TOOL_ROUND_TRIPS = 8`；用 `HttpRequest.builder()` 组装请求、经 `CapabilityDispatcher.invoke/extract` 调用与取值；`try/catch(ClaraException)` 归一为终态、`finally` 解绑 `ConvContext`；token 用量由 `TokenUsage(input, output, total, cached)` 组装。

**`ConversationRequest`（`com.clara.orchestration.api`）**
- 作用：一次对话请求的入参（会话标识、用户输入、能力种类、control 配置等）。
- 使用范围：编排入口的输入载体。
- 为什么需要：统一编排入参，屏蔽客户端调用细节。
- 业务细节：携带用户标识、会话标识、用户输入内容、能力种类（CHAT / IMAGE / VIDEO / AUDIO）、control 配置（窗口 / 压缩 / 向量化 / 检索）与是否流式；构造期校验会话标识非空。
- 技术细节：`@Data @Builder`，含 12 个字段（`userId`、`conversationId`、`provider`、`model`、`capabilityKind`、`userInput`、`providerConfiguration`、`control`、`stream`、`observer`、`maxToolRoundTrips`）；字段校验发生在编排入口（`conversationId` 非空白、`model`/`userInput` 非空），缺省回落为 `capabilityKind=CHAT`、`control=ContextControlConfig.defaults()`、`maxToolRoundTrips=默认上限`。

**`ConversationResult`（`com.clara.orchestration.api`）**
- 作用：一次对话的结果（回复内容、编排状态、工具往返次数、token 用量）。
- 使用范围：编排入口的返回载体。
- 为什么需要：统一返回编排终态与产出，供宿主展示与记录。
- 业务细节：携带最终回复内容（文本，或多模态产物引用）、`OrchestrationStatusEnum` 状态、工具往返次数、token 用量（输入 / 输出 / 总计）；失败时携带错误信息。
- 技术细节：`@Data @Builder`，字段 `conversationId` / `content` / `status` / `toolRoundTrips` / `usage`(`TokenUsage`) / `error`(`ClaraError`)；由编排器私有静态工厂 `conversationResult(...)` 统一构造。

**`OrchestrationStatusEnum`（`com.clara.orchestration.api`）**
- 作用：标识编排终态（完成 / 取消 / 失败）。
- 使用范围：`ConversationResult` 的状态字段。
- 为什么需要：统一编排生命周期取值，区分正常完成与取消 / 失败。
- 业务细节：`COMPLETED` 表示已产出回复；`CANCELLED` 表示执行中被取消（回复可能不完整）；`FAILED` 表示异常终止（附错误信息）。
- 技术细节：三态枚举（`COMPLETED` / `CANCELLED` / `FAILED`），无附加字段与方法。

### 4.2 上下文装配

**`ContextAssembler`（`com.clara.orchestration.context`）**
- 作用：装配本轮下发给模型的消息列表（system 指令 + 窗口内历史 + 压缩摘要 + 检索注入 + 技能提示词）。
- 使用范围：每轮模型调用前。
- 为什么需要：把「历史恢复、窗口、压缩、检索注入、技能装配」组合成一份请求消息，是编排链路的核心装配点。
- 业务细节：输入为窗口记忆实例、当前用户输入与 control 配置；依次执行「窗口裁剪（只读，不改底层存储）→ 超限压缩融合摘要 → 技能提示词注入 → 对话知识找回注入」；system 指令始终归一化到消息首位；输出消息列表供 `CapabilityDispatcher` 使用。
- 技术细节：构造注入 3 个协作者（`WindowCompressionCoordinator` / `SemanticRecallCoordinator` / `SkillMatchCoordinator`）；方法 `AssembledContext assemble(IWindowMemory, ContextControlConfig, ITokenCountEstimator)`；把 control 转为 `WindowPolicy`，用 `windowMemory.messages()`（全量）与 `windowMemory.window(policy, estimator)`（裁剪）的差集求被淘汰消息（`IdentityHashMap` 身份集合，避免 equals 误判）；取历史中最后一条 `UserContent` 文本作为检索/匹配查询；system 文本按「技能提示词 → 压缩摘要 → 检索知识」拼接，非空才构造 `SystemContent` 并置于首位；返回独立不可变 `AssembledContext`（`messages` + `tools`(`ToolContext`)）。

**`WindowCompressionCoordinator`（`com.clara.orchestration.context`）**
- 作用：历史命中窗口上限时，取出被淘汰消息组、触发压缩、把返回的摘要并入 system 指令。
- 使用范围：发送请求前、窗口超限时。
- 为什么需要：编排层负责「何时压缩、压缩结果如何回填」，具体压缩逻辑由 `ISummarizeCoordinator`（memory）实现。
- 业务细节：仅在窗口裁剪判定发生淘汰时触发；取被淘汰的最旧连续消息组（整组，含完整工具往返）交给 `ISummarizeCoordinator.summarize`；返回的 `SystemContent` 摘要并入 system 指令、替代被淘汰部分；摘要请求不进入历史、不再次触发压缩、不产生调用记录（防递归）。
- 技术细节：构造注入 `ISummarizeCoordinator`（可为 null）；方法 `IContent summarize(List<IContent> evicted)`；未配置压缩实现或淘汰集合为 null/空时直接返回 null（跳过压缩），否则委托 `ISummarizeCoordinator` 并原样返回摘要内容。

**`SemanticRecallCoordinator`（`com.clara.orchestration.context`）**
- 作用：用当前输入向量按会话检索被淘汰的历史知识，命中的相关知识作为补充上下文注入。
- 使用范围：发送请求前。
- 为什么需要：长对话的知识召回需要编排层在发送前完成检索与注入，检索能力由 `ISemanticMemoryStore` 提供。
- 业务细节：用当前用户输入经 `IEmbeddingClient` 向量化，构造 `SemanticMemoryQuery`（来源 `CONVERSATION`、限定当前会话、topK 与相似度阈值取自 control 配置）调用 `ISemanticMemoryStore.search`；命中知识拼装为补充上下文注入；无命中则返回空、不注入。
- 技术细节：构造注入 `IEmbeddingClient` + `ISemanticMemoryStore`（均可为 null）；方法 `List<String> recall(conversationId, userInput, control)`；任一依赖为 null、`retrievalCount<=0` 或输入为空即返回空列表；否则 embed → 构造 `SemanticMemoryQuery(CONVERSATION, conversationId, vector, retrievalCount, similarityThreshold, now, null)` → `search` → 收集 `score >= 阈值` 且 `content` 非空的条目文本。

### 4.3 技能匹配

**`SkillMatchCoordinator`（`com.clara.orchestration.skill`）**
- 作用：用用户输入向量检索技能描述，选出本轮要放入的提示词与对应工具方法。
- 使用范围：发送请求前。
- 为什么需要：编排层按匹配结果动态装配技能能力（提示词 + 工具声明），避免全量加载；检索走 `ISemanticMemoryStore`，取回走 `IPromptProvider`。
- 业务细节：用当前用户输入向量化，构造 `SemanticMemoryQuery`（来源 `PROMPT` / `TOOL_DESCRIPTION`、topK 与阈值取自 control 配置）检索；按相似度排序、过滤低于阈值的命中、同技能去重；命中的提示词经 `IPromptProvider` 取回定义；命中的工具方法组装为请求的 `tools` 声明；无命中则不注入任何技能。
- 技术细节：构造注入 `IEmbeddingClient` / `ISemanticMemoryStore` / `IPromptProvider`（均可为 null）；方法 `MatchedSkill match(userInput, control)`；分别按 `PROMPT`、`TOOL_DESCRIPTION` 各检索一次（`SemanticMemoryQuery(sourceType, null 会话, vector, retrievalCount, threshold, now, null)`），用 `LinkedHashSet<sourceId>` 去重、`score>=阈值` 过滤；提示词经 `IPromptProvider.prompt(sourceId)` 取回；工具方法把 `sourceId` 规整为合法工具名（仅 `[a-zA-Z0-9_-]`、≤128 字符、空则 `skill_tool`）并附空 object schema 组装 `ToolContext`/`ToolSpecification`；返回内嵌不可变 `MatchedSkill`。

### 4.4 工具往返

**`ToolRoundTripCoordinator`（`com.clara.orchestration.tool`）**
- 作用：检测模型响应中的工具调用，逐个执行、按 `callId` 构造工具结果消息回填，并驱动再次请求直至无工具调用。
- 使用范围：模型响应含 tool_calls 时。
- 为什么需要：工具往返是编排的循环职责，屏蔽客户端手工拼装；本地工具走 `IToolInvoker`（skill），外部工具走 clara-mcp。
- 业务细节：从 `HttpResponse` 提取工具调用（Chat 的 `toolCalls` 与 Responses 的 `responsesToolCalls` 统一为 `IToolInvocation`）；本地工具经 `IToolInvoker.invoke` 执行得到 `ToolResult`、外部工具经 clara-mcp 调用；助手侧构造 `ToolCallContent`（保留调用标识）、结果侧按同一 `callId` 构造 `ToolContent` 回填，保证一一对应；执行无需人工审批；达到往返次数上限时抛编排错误终止。
- 技术细节：构造注入 `IToolInvoker`（非空校验）；对外 `boolean hasToolCalls(HttpResponse)`（null 或无调用返回 false）与 `List<IContent> perform(HttpResponse)`；`perform` 把 Chat 的 `toolCalls` 与 Responses 的 `responsesToolCalls` 统一为 `List<ToolCall>`（Responses 逐项构造 `ToolCallFunction` + `ToolCall`），逐个 `IToolInvoker.invoke` 后按调用顺序返回「`ToolCallContent`（助手调用）在前、各 `ToolContent`（结果，`callId` 对齐）在后」的消息列表。

### 4.5 多模态能力

**`CapabilityDispatcher`（`com.clara.orchestration.capability`）**
- 作用：按能力种类分派模型调用（CHAT / IMAGE / VIDEO / AUDIO），产出对应的响应内容。
- 使用范围：编排需要非文本能力时。
- 为什么需要：多模态能力纳入统一编排，编排层按能力种类选择请求构建与结果提取方式，不分散在各处。
- 业务细节：按 `CapabilityKindEnum` 分派——CHAT 走文本对话链路；IMAGE（文生图 / 图生图）、VIDEO、AUDIO 走各自请求构建（提示词 / 图片 / 参数）与结果提取（`HttpResponse.images` 等）；所有能力统一经 `IStreamModel` 调用入口，并交由 `CallRecordPublisher` 统一记录。
- 技术细节：构造注入 `IStreamModel`（非空校验）；方法 `HttpResponse invoke(HttpRequest)`（同步，`streamModel.chat(request, HttpResponse.class)`）与 `HttpResponse invoke(HttpRequest, IStreamObserver)`（observer 非 null 走流式并取 `handle.result()`，否则退化为同步）；`String extract(CapabilityKindEnum, HttpResponse)`——CHAT 取 `content`、IMAGE 拼接 `images[].url` 或 `b64Json`（换行分隔）、其余取 `content`；流式失败 / 取消时返回 null。

### 4.6 调用记录

**`CallRecordPublisher`（`com.clara.orchestration.record`）**
- 作用：在每次模型响应结束后，用本次实际请求与响应对象构造 `CallRecord` 并交给 `ICallRecordSink`。
- 使用范围：每次模型调用结束时（同步 / 流式各一条）。
- 为什么需要：调用留痕是后续「对话总结 / 理解」的原始依据；未配置接收方时不产生记录、也不影响调用。
- 业务细节：响应结束后以本次实际执行的请求对象与响应对象（不产生第二份）构造 `CallRecord(request, response, provider, modelType)`；`ICallRecordSink` 未配置（null）时直接跳过；记录过程不抛异常、不影响调用本身。
- 技术细节：构造注入 `ICallRecordSink`（可为 null）；方法 `void publish(IRequest, IResponse, ModelProviderEnum, String)`；sink 为 null 或 request/response/provider 为 null 时直接 return，否则 `new CallRecord(...)` 后 `sink.accept(...)`，不传播异常。

### 4.7 工作流

**`WorkflowStepInvoker`（`com.clara.orchestration.workflow`）**
- 作用：把某个工作流作为编排中的一个步骤调用，用输入启动实例并取回结果。
- 使用范围：编排需要执行确定性流程时。
- 为什么需要：编排可复用工作流能力，调用走 `IWorkflowEngine`（clara-workflow）。
- 业务细节：用工作流定义与输入调用 `IWorkflowEngine.start(definition, instanceId, inputs)` 启动实例；实例进入等待人工输入时经恢复流程续跑；取回 `WorkflowResult` 后提取输出作为编排中的一步产物。
- 技术细节：构造注入 `IWorkflowEngine` + `IWorkflowDefinitionResolver`（非空校验）；`WorkflowResult invoke(instanceId, inputs)`（resolve 定义 → `engine.start`）与 `WorkflowResult resume(instanceId, inputs, expectedCheckpointVersion)`（resolve → `engine.resume` 带检查点版本校验）。

### 4.8 错误

**`OrchestrationErrors`（`com.clara.orchestration`）**
- 作用：集中创建编排领域错误。
- 使用范围：本模块内部。
- 为什么需要：统一错误码与分类。
- 业务细节：覆盖入参非法、能力不受支持、工具往返超限等编排错误，统一错误码与分类，供上层识别与处理。
- 技术细节：私有构造 + 静态工厂，返回 `ClaraException(ClaraError)`；`invalidArgument`（`INVALID_ARGUMENT` / `VALIDATION`）、`capabilityNotSupported`（`CAPABILITY_NOT_SUPPORTED` / `CAPABILITY`）、`toolRoundTripLimitExceeded`（`INVALID_ARGUMENT` / `RESOURCE_LIMIT`），均标记为不可重试。

## 5. 关键实现细节

### 5.1 Agent Loop 主流程（`DefaultConversationOrchestrator`）

1. **绑定会话上下文**：以请求的会话标识构造 `ConvContext`（携带平台配置与能力种类），用 `ConvContext.open(...)` 绑定到当前线程；会话标识与 clara-memory 的会话标识同源同值；
2. **恢复历史**：按「会话 + 供应商 + 模型」取该会话的窗口记忆实例（由 clara-memory 提供），加载历史；
3. **装配上下文**：`ContextAssembler` 依次完成窗口裁剪、超限压缩、技能匹配装配、对话知识找回注入，产出本轮 `messages`；
4. **模型调用**：按能力种类经 `CapabilityDispatcher` 组装 `HttpRequest` 调用 `IStreamModel`（同步或流式），拿到 `HttpResponse`；
5. **调用记录**：`CallRecordPublisher` 在响应结束后构造 `CallRecord` 交给 `ICallRecordSink`；
6. **追加历史 + 工具往返**：把助手回复追加进历史；若响应含工具调用，`ToolRoundTripCoordinator` 执行并回填后**回到第 3 步**再次请求，直至无工具调用；
7. **收尾**：产物封装为 `ConversationResult` 返回；`ConvContext.clear()` 解绑。

**取消**：全链路复用 `ConvContext` 的取消令牌，循环体与关键点调用 `throwIfCancelled()` 快速失败；取消后编排以「已取消」终态收尾。

### 5.2 上下文装配（`ContextAssembler`）

- **窗口裁剪**：调 `IWindowMemory.window(policy, estimator)` 取窗口内历史（只读裁剪）；窗口策略与 token 估算来自 control 配置（`ContextControlConfig` → `WindowPolicy` / `ITokenCountEstimator`）；
- **超限压缩**：窗口裁剪判定发生淘汰时，`WindowCompressionCoordinator` 取出被淘汰的连续消息组，调 `ISummarizeCoordinator.summarize(evicted)` 生成摘要，以 `SystemContent` 并入 system 指令；摘要请求本身不进入历史、不再次触发压缩、不产生记录（防递归）；
- **技能装配**：`SkillMatchCoordinator` 给出的提示词作为 system 指令的一部分注入，工具方法组装为请求的 `tools` 声明；
- **检索注入**：`SemanticRecallCoordinator` 命中的知识作为补充上下文注入；
- **system 归一化**：system 指令始终位于消息首位。

### 5.3 技能匹配（`SkillMatchCoordinator`）

- 用当前用户输入经 `IEmbeddingClient` 向量化，构造 `SemanticMemoryQuery`（来源限定 `PROMPT` / `TOOL_DESCRIPTION`、topK、相似度阈值，均来自 control 配置）调用 `ISemanticMemoryStore.search`；
- 按相似度排序并过滤低于阈值的命中、同技能去重；
- 命中的提示词经 `IPromptProvider` 取回定义，命中的工具方法组装为模型请求的工具声明，交由 `ContextAssembler` 注入。

### 5.4 工具往返（`ToolRoundTripCoordinator`）

- 从 `HttpResponse` 提取工具调用（Chat 的 `toolCalls` / Responses 的 `responsesToolCalls`，统一为 `IToolInvocation`）；
- 本地工具：经 `IToolInvoker.invoke(invocation)` 执行，得到 `ToolResult`；外部工具：经 clara-mcp 调用；
- 按 `callId` 构造 `ToolContent` 回填进历史，助手侧的 `ToolCallContent` 保留调用标识，保证与调用一一对应；
- 回填后再次请求，直至无工具调用；多模态能力调用（图片 / 视频 / 音频）由 `CapabilityDispatcher` 处理，无需人工审批。

### 5.5 多模态能力（`CapabilityDispatcher`）

- 按 `CapabilityKindEnum` 分派：CHAT 走对话链路；IMAGE（文生图 / 图生图）、VIDEO、AUDIO 走各自请求构建与结果提取（`HttpResponse.images` 等）；
- 所有能力统一走 `IStreamModel` 调用入口与调用记录，纳入同一编排流程。

### 5.6 调用记录（`CallRecordPublisher`）

- 同步与流式各记一条，记录时机在响应结束之后；
- 记录内容严格四项：本次请求对象、本次响应对象、供应商类型、模型类型；请求对象与本次实际执行的请求对象保持一致（不产生第二份）；
- 未配置 `ICallRecordSink` 时不产生记录、也不影响调用本身。

### 5.7 工作流调用（`WorkflowStepInvoker`）

- 用 `IWorkflowEngine.start(definition, instanceId, inputs)` 启动工作流实例，等待或恢复后取回 `WorkflowResult`；
- 工作流定义由宿主 / 上层提供（`IWorkflowDefinitionResolver` 解析），编排层只负责作为一步调用并消费结果。

### 5.8 宿主装配（`clara-application`）

- `clara-application` 负责装配所有模块（core / model / memory / skill / workflow / mcp / orchestration），构造 `DefaultConversationOrchestrator` 及其协作者并注入；
- 具体装配与入口形态在 `clara-application` 的技术设计中细化。

## 6. 业务场景与功能映射

### 6.1 业务职责

对话编排（Agent Loop）是 orchestration 的唯一核心职责：把模型 / 记忆 / 技能 / 工作流 / mcp 等能力组合成一条完整的对话流程。编排层只做组合，不实现任何具体能力。除核心对话编排外，还包含两项业务职责：可把工作流作为一个步骤调用；工具往返中可通过 mcp 与外部服务联动。

### 6.2 典型业务场景（业务流程 → 技术落点）

**场景一：无工具的单轮对话**
用户提问 → 绑定会话上下文、恢复历史 → 装配上下文 → 调模型 → 记录一条 → 助手回复追加进历史 → 返回用户。
落点：`DefaultConversationOrchestrator` → `ContextAssembler` → `CapabilityDispatcher` → `CallRecordPublisher`。

**场景二：含工具往返**
用户提问 → 装配上下文 → 调模型 → 响应含工具调用 → 记录一条 → 构建「助手请求工具」消息进历史 → 执行工具（本地 skill / 外部 mcp）→ 构建「工具结果」消息（`callId` 对齐）进历史 → 再次请求 → 直至无工具调用 → 返回。
落点：`ToolRoundTripCoordinator`（经 `IToolInvoker` 调本地工具、经 clara-mcp 调外部工具）。

**场景三：超限压缩 + 向量化召回**
发送请求前历史命中窗口上限 → 取出最旧的连续消息组 → 压缩为一条摘要并入 system 指令 → 被淘汰原始消息向量化入库（关联当前会话）→ 检索（限定当前会话）→ 命中知识作为补充上下文注入。
落点：`WindowCompressionCoordinator`（压缩）+ `SemanticRecallCoordinator`（召回）。

**场景四：多模态能力调用**
识别 IMAGE / VIDEO / AUDIO 能力 → 按能力组装请求 → 统一模型调用入口 → 记录调用 → 结果（图片 / 视频 / 音频）纳入编排流程。
落点：`CapabilityDispatcher`。

**场景五：调用工作流**
编排需要执行确定性流程 → 用输入启动工作流实例 → 取回结果作为编排中的一步产物。
落点：`WorkflowStepInvoker`（经 `IWorkflowEngine`）。

### 6.3 功能需求映射（业务 FR → 技术实现）

| 业务需求 | 实现落点 |
| --- | --- |
| FR-O1 会话历史恢复 | `DefaultConversationOrchestrator` 绑定 `ConvContext` + 窗口记忆实例加载 |
| FR-O2 对话链路编排 | `ContextAssembler`（窗口 / 压缩 / 技能 / 检索）+ `CapabilityDispatcher` + `CallRecordPublisher` |
| FR-O3 工具往返循环 | `ToolRoundTripCoordinator` |
| FR-O4 调用工作流 | `WorkflowStepInvoker` |
| FR-O5 mcp 联动 | `ToolRoundTripCoordinator`（外部工具）经 clara-mcp 网关 |
| FR-O6 多模态能力编排 | `CapabilityDispatcher` |

### 6.4 业务规则与决策的落点

| 业务规则 / 决策 | 落点 |
| --- | --- |
| 压缩是一次性任务、不走 RAG 递归，摘要请求不进入历史 / 不再次触发压缩 / 不产生记录 | `WindowCompressionCoordinator` |
| 窗口限制、压缩、技能匹配、知识找回均在发送请求前触发 | `ContextAssembler` 的调用时机 |
| 工具往返由编排层驱动、直至无工具调用；执行无需人工审批 | `ToolRoundTripCoordinator`（循环终止条件为无工具调用） |
| 会话历史由 ConvContext 调 memory 恢复，压缩记录为恢复起点 | `DefaultConversationOrchestrator` 的历史恢复步骤 |
| 调用记录在响应结束后产生，同步 / 流式各一条，严格四项 | `CallRecordPublisher` |
| 编排只组合、不实现具体能力，各能力通过 core 接口调用 | 全模块（构造器注入 core 契约） |

## 7. 非目标

- 不实现具体能力（模型调用、记忆、技能、mcp、工作流均由对应模块实现）；
- 不承载工作流 / 任务规划 / 交互（由 clara-workflow 负责），现有 orchestration 下旧的 `interaction` / `planner` 代码在本阶段重写时清理；
- 不涉及观测日志系统（脱敏 / 截断 / SLF4J 等）。
