# clara-core 契约 — 技术设计文档

> 状态：已审核
> 前置：`conversation-context-call-record-business-design.md` 及各 service 业务设计文档（均已「已审核」）
> 本文约定 core 层的包结构、命名规范、契约清单（含每个类型的作用 / 使用范围 / 必要性）与去租户方案。

## 1. 包结构与命名规范（全局约定）

按类型分包的约定如下：

| 元素 | 包 | 命名规则 | 示例 |
| --- | --- | --- | --- |
| 接口 | `com.clara.core.<domain>` | 首字母 `I` | `com.clara.core.memory.IWindowMemory` |
| 异常 | `com.clara.exception` | 以 `Exception` 结尾 | `com.clara.exception.UnknownSideEffectException` |
| DTO | `com.clara.dto.<domain>` | 无前后缀 | `com.clara.dto.memory.MemoryEntry` |
| 枚举 | `com.clara.enums.<domain>` | 以 `Enum` 结尾 | `com.clara.enums.workflow.WorkflowInstanceStatusEnum` |
| 服务实现 | 各模块包（如 `com.clara.workflow`） | 无前后缀，常加领域前缀或 `Default` | `DefaultWorkflowEngine` |

领域子包划分：`record`（调用记录）、`memory`（窗口 / 压缩 / 语义记忆 / 嵌入）、`skill`（提示词 / 工具）、`workflow`（工作流）、`planner`（任务规划）、`interaction`（交互）。

**约束：**

1. `com.clara.core.*` 只放接口（`I` 前缀），不得放具体服务实现；异常一律放 `com.clara.exception`（以 `Exception` 结尾），DTO 一律放 `com.clara.dto.*`、枚举一律放 `com.clara.enums.*`。
2. **尽量少使用内部类型（inner / nested type）**：契约与实现中的类型尽量定义为独立顶层类；确需组合的类型通过字段引用，而非嵌套定义。例如工作流节点实现（ActionNode / CompositeNode 等）独立成顶层类，归入 clara-workflow，不作为内部类。
3. **DTO 尽量使用 Lombok 注解生成访问器**：`com.clara.dto.*` 下的 DTO 优先使用 `@Data` / `@Getter` / `@Builder` 等 Lombok 注解，不手写 getter / setter。

## 2. 模块划分与依赖

```
clara-core         —— 接口契约 + DTO + 枚举，无业务实现
clara-model        —— 模型编排 / 协议适配（保留，落地 IEmbeddingClient / ITokenCountEstimator 实现）
clara-mcp          —— 外部服务联动（保留）
clara-workflow     —— 工作流 / 任务规划 / 交互（新建，依赖 core + mcp）
clara-memory       —— 窗口 / 压缩 / 语义记忆 / 会话持久化（重写，依赖 core）
clara-skill        —— 技能 / 提示词 / 工具方法脚本执行（Python / JavaScript / Shell，重写，依赖 core）
clara-orchestration —— 对话编排 Agent Loop（后续，依赖 core/model/memory/skill/workflow/mcp）
clara-application  —— 程序入口（后续，组合所有模块）
```

核心原则：core 只定义契约，各模块实现 core 接口；上层通过 core 接口调用，不感知具体实现。除 clara-model 与 clara-core 外，其余模块现有代码仅作参考、全部重写、不做兼容。

## 3. 文件结构（要创建的契约文件）

```
clara-core/src/main/java/com/clara/
├── core/                        # 接口（I 前缀）
│   ├── record/
│   │   └── ICallRecordSink.java
│   ├── memory/
│   │   ├── IWindowMemory.java
│   │   ├── ITokenCountEstimator.java
│   │   ├── ISummarizeCoordinator.java
│   │   ├── IEmbeddingClient.java
│   │   └── ISemanticMemoryStore.java
│   ├── skill/
│   │   ├── IPromptProvider.java
│   │   ├── IToolInvoker.java
│   │   └── IToolExecutor.java
│   ├── workflow/
│   │   ├── IWorkflowEngine.java
│   │   ├── IWorkflowNode.java
│   │   ├── IWorkflowAction.java
│   │   ├── IScopeCondition.java
│   │   └── ICheckpointStore.java
│   ├── planner/
│   │   ├── ITaskPlanEngine.java
│   │   ├── ITaskHandler.java
│   │   ├── IBudgetLedger.java
│   │   ├── IExecutionLedger.java
│   │   └── ICapabilityAuthorizer.java
│   └── interaction/
│       ├── IInteractionService.java
│       ├── IUserInteractionGateway.java
│       ├── IAnswerSchemaValidator.java
│       ├── IInteractionResumeCoordinator.java
│       ├── IWorkflowDefinitionResolver.java
│       └── ITimeSource.java
├── exception/                   # 异常（Exception 结尾）
│   └── UnknownSideEffectException.java
├── dto/                         # DTO（Lombok 注解，不手写 getter/setter）
│   ├── record/
│   │   └── CallRecord.java
│   ├── memory/
│   │   ├── WindowPolicy.java
│   │   ├── MemoryEntry.java
│   │   ├── MemoryMatch.java
│   │   ├── SemanticMemoryQuery.java
│   │   └── ContextControlConfig.java
│   ├── skill/
│   │   ├── ToolMethod.java
│   │   └── PromptDefinition.java
│   ├── workflow/
│   │   ├── WorkflowDefinition.java
│   │   ├── WorkflowResult.java
│   │   ├── WorkflowCheckpoint.java
│   │   └── ExecutionScope.java
│   ├── planner/
│   │   ├── TaskPlan.java
│   │   ├── TaskNode.java
│   │   ├── TaskPlanResult.java
│   │   ├── TaskExecutionContext.java
│   │   ├── TaskExecutionResult.java
│   │   ├── ExecutionRecord.java
│   │   ├── PlanLimits.java
│   │   ├── RetryPolicy.java
│   │   ├── BudgetAmount.java
│   │   ├── BudgetSnapshot.java
│   │   └── BudgetReservation.java
│   └── interaction/
│       ├── InteractionContext.java
│       ├── InteractionFeedback.java
│       ├── InteractionRequest.java
│       ├── InteractionResponse.java
│       ├── Question.java
│       ├── InteractionOption.java
│       ├── FeedbackResult.java
│       └── ResumeOutcome.java
└── enums/                       # 枚举（Enum 后缀）
    ├── memory/
    │   └── MemorySourceTypeEnum.java
    ├── skill/
    │   └── ScriptTypeEnum.java
    ├── workflow/
    │   ├── WorkflowInstanceStatusEnum.java
    │   └── WorkflowNodeTypeEnum.java
    ├── planner/
    │   ├── PlanStatusEnum.java
    │   ├── TaskNodeStatusEnum.java
    │   ├── PlanActionEnum.java
    │   ├── BudgetReservationStatusEnum.java
    │   └── ExecutionRecordStatusEnum.java
    └── interaction/
        ├── InteractionTypeEnum.java
        └── InteractionStateEnum.java
```

## 4. 契约清单（每个类型含作用 / 使用范围 / 为什么需要）

### 4.1 record（调用记录）

**接口 `ICallRecordSink`（`com.clara.core.record`）**
- 作用：接收一次模型调用结束后的调用记录。
- 使用范围：clara-model 在响应结束时产生记录并交给本接口；宿主应用负责实现（写库 / 日志等）。
- 为什么需要：把「产生记录」与「记录落到哪里」解耦，core 只定义接收契约，不依赖任何存储实现。

**DTO `CallRecord`（`com.clara.dto.record`）**
- 作用：承载一次模型调用的留痕数据（请求、响应、供应商类型、模型类型）。
- 使用范围：clara-model 产生 → clara-orchestration 消费（对话总结 / 理解）。
- 为什么需要：作为后续「对话总结 / 理解」的原始依据，是严格四字段的不可变值对象。

### 4.2 memory（窗口 / 压缩 / 语义记忆 / 嵌入）

**接口 `IWindowMemory`（`com.clara.core.memory`）**
- 作用：以会话为单位管理短时历史消息，执行窗口限制与淘汰。
- 使用范围：对话编排发送请求前取窗口内历史；由 clara-memory 实现。
- 为什么需要：统一窗口规则，客户端无需手工拼装历史；core 只定义契约、不依赖实现。

**DTO `WindowPolicy`（`com.clara.dto.memory`）**
- 作用：描述窗口限制配置（条数 / 大小 / 百分比三维度）。
- 使用范围：来自平台 control 配置 → 传给窗口执行裁剪。
- 为什么需要：三种维度与默认值的配置载体，与模型类型同级别、不同模型可不同。

**接口 `ITokenCountEstimator`（`com.clara.core.memory`）**
- 作用：估算一批历史消息的 token 数。
- 使用范围：按百分比维度裁剪时作为计算依据；由调用方提供实现。
- 为什么需要：百分比维度依赖 token 估算，估算规则随模型而异，须可注入、不在 core 写死。

**接口 `ISummarizeCoordinator`（`com.clara.core.memory`）**
- 作用：把超限被淘汰的消息组总结为一条 system 摘要。
- 使用范围：窗口超限、发送请求前触发；由 clara-memory 编排。
- 为什么需要：压缩涉及模型调用与提示词，将「编排」与「模型 / 提示词实现」解耦。

**接口 `IEmbeddingClient`（`com.clara.core.memory`）**
- 作用：把文本向量化（走云端嵌入 API）。
- 使用范围：语义记忆入库、技能描述向量化之前；由 clara-model 侧或宿主装配。
- 为什么需要：memory 只负责存储与检索、不负责向量化，嵌入平台可配置。

**接口 `ISemanticMemoryStore`（`com.clara.core.memory`）**
- 作用：语义记忆条目的写入（upsert）、检索（search）、按条目删除（deleteById）与按会话删除（deleteByConversation，仅删除 CONVERSATION 来源条目）。
- 使用范围：技能匹配检索、对话知识召回；由 clara-memory 提供 Local / Milvus 实现。
- 为什么需要：统一双后端接口，上层不感知具体向量库；按条目删除支撑技能热加载的移除，按会话删除限定来源避免误删全局技能描述。

**DTO `MemoryEntry`（`com.clara.dto.memory`）**
- 作用：向量库中的一条记忆条目（标识 + 正文 + 向量 + 会话关联 + 元数据）。
- 使用范围：语义记忆写入的载体；无租户、仅按会话关联。
- 为什么需要：语义记忆读写的数据结构，是写入与检索的公共单位；标识支撑 upsert 与按条目删除。标识由写入方确定——技能条目用 `{来源类型}:{技能ID}`，对话条目由写入方生成。

**DTO `MemoryMatch`（`com.clara.dto.memory`）**
- 作用：一次检索命中的条目及其相似度得分。
- 使用范围：语义记忆检索的返回项。
- 为什么需要：检索结果需要「条目 + 得分」以排序与过滤。

**DTO `SemanticMemoryQuery`（`com.clara.dto.memory`）**
- 作用：描述一次向量检索的过滤与召回参数。
- 使用范围：语义记忆检索时作为入参。
- 为什么需要：统一来源类型 / 会话 / 向量 / topK / 阈值 / 时间 / 元数据过滤，避免散参数。

**DTO `ContextControlConfig`（`com.clara.dto.memory`）**
- 作用：承载上下文历史处理的完整控制配置（窗口、压缩、向量化、检索）。
- 使用范围：来自 clara-model 的 control 节点 → 传给 memory 的窗口 / 压缩 / 语义记忆。
- 为什么需要：统一承载窗口之外的压缩开关、向量化开关、检索条数、相似度阈值等设置，避免散参数。

**枚举 `MemorySourceTypeEnum`（`com.clara.enums.memory`）**
- 作用：标识语义记忆的来源类型。
- 使用范围：MemoryEntry / SemanticMemoryQuery 的字段取值。
- 为什么需要：语义来源固定三种（提示词 / 工具描述 / 对话记录），用枚举约束合法取值。

### 4.3 skill（提示词 / 工具）

**接口 `IPromptProvider`（`com.clara.core.skill`）**
- 作用：按标识取回提示词定义；提供内置总结提示词（`summarizePrompt()`，可被运行目录同名覆盖）。
- 使用范围：上下文超限压缩取总结提示词；由 clara-skill 实现。
- 为什么需要：core 不依赖 skill 的存储与文件格式，提示词获取走接口。

**接口 `IToolInvoker`（`com.clara.core.skill`）**
- 作用：框架调用技能声明的工具方法（工具往返统一入口）。
- 使用范围：对话编排工具往返循环；由 clara-skill 实现。
- 为什么需要：屏蔽工具方法定位与执行细节，统一工具调用视图。

**接口 `IToolExecutor`（`com.clara.core.skill`）**
- 作用：执行工具方法对应的脚本（Python / JavaScript / Shell）并返回结果。
- 使用范围：工具方法实际执行；由 clara-skill 实现。
- 为什么需要：统一脚本执行与安全隔离，屏蔽不同运行环境差异，与「调用」职责分离。

**DTO `ToolMethod`（`com.clara.dto.skill`）**
- 作用：工具方法的完整定义（标识、名称、运行类型、描述、参数 Schema、脚本引用、元数据）。
- 使用范围：技能注册 / 加载、工具调用与执行的载体。
- 为什么需要：统一工具方法的识别描述信息，屏蔽不同脚本运行环境差异。

**DTO `PromptDefinition`（`com.clara.dto.skill`）**
- 作用：提示词的完整定义（标识、描述、多段多角色文本）。
- 使用范围：提示词加载与获取（含总结提示词）。
- 为什么需要：提示词的结构化描述，供向量化匹配与按标识取回。

**枚举 `ScriptTypeEnum`（`com.clara.enums.skill`）**
- 作用：标识工具方法脚本的运行类型。
- 使用范围：ToolMethod 的 `type` 字段取值。
- 为什么需要：脚本运行类型固定三种（Python / JavaScript / Shell），用枚举约束合法取值。

### 4.4 workflow（工作流）

**接口 `IWorkflowEngine`（`com.clara.core.workflow`）**
- 作用：执行确定性工作流，支持暂停 / 人工输入 / 恢复 / 取消。
- 使用范围：对话编排调用工作流；由 clara-workflow 实现。
- 为什么需要：core 定义执行契约，工作流引擎实现与调用方解耦。

**接口 `IWorkflowNode`（`com.clara.core.workflow`）**
- 作用：工作流定义图中的单个节点（标识、类型、输出键、子节点）。
- 使用范围：WorkflowDefinition 遍历节点；由 clara-workflow 提供具体节点实现。
- 为什么需要：让定义遍历不依赖具体节点类型，节点实现可独立演进。

**接口 `IWorkflowAction`（`com.clara.core.workflow`）**
- 作用：动作节点的执行逻辑（读取作用域、返回输出）。
- 使用范围：AGENT / TOOL 节点的行为；由调用方或模块实现。
- 为什么需要：动作逻辑与引擎解耦，动作可复用与替换。

**接口 `IScopeCondition`（`com.clara.core.workflow`）**
- 作用：基于执行作用域求值布尔条件。
- 使用范围：条件分支与循环退出判断；由调用方实现。
- 为什么需要：条件逻辑与引擎解耦。

**接口 `ICheckpointStore`（`com.clara.core.workflow`）**
- 作用：保存并读取工作流实例的可恢复状态快照。
- 使用范围：工作流暂停 / 恢复时的持久化；由 clara-workflow 提供实现。
- 为什么需要：检查点是恢复依据，存储后端可替换（内存 / 持久化）。

**DTO `WorkflowDefinition`（`com.clara.dto.workflow`）**
- 作用：描述工作流的结构与元数据（标识 / 版本 / Schema / 根节点）。
- 使用范围：引擎启动 / 恢复时作为入参。
- 为什么需要：工作流的静态定义载体，构造期校验无环、节点 ID 唯一。

**DTO `WorkflowResult`（`com.clara.dto.workflow`）**
- 作用：封装一次工作流执行后的状态快照。
- 使用范围：引擎 start / resume / cancel / suspend 的返回值。
- 为什么需要：统一返回执行状态、作用域与等待输入信息。

**DTO `WorkflowCheckpoint`（`com.clara.dto.workflow`）**
- 作用：工作流实例的可恢复状态快照。
- 使用范围：暂停 / 恢复时经检查点存储读写。
- 为什么需要：恢复执行的依据，含实例 / 工作流标识、版本与作用域。

**DTO `ExecutionScope`（`com.clara.dto.workflow`）**
- 作用：工作流执行期间累积的变量、节点结果、完成标识与控制状态。
- 使用范围：执行期间随结果 / 检查点传递。
- 为什么需要：执行状态的统一容器，是结果与检查点的组成部分。

**枚举 `WorkflowInstanceStatusEnum`（`com.clara.enums.workflow`）**
- 作用：标识工作流实例状态（含终态判断）。
- 使用范围：WorkflowResult / WorkflowCheckpoint 的状态字段。
- 为什么需要：统一状态取值与终态判断逻辑。

**枚举 `WorkflowNodeTypeEnum`（`com.clara.enums.workflow`）**
- 作用：标识工作流节点类型。
- 使用范围：IWorkflowNode.getType() 返回值。
- 为什么需要：引擎按类型分派执行，枚举约束合法节点类型。

> 节点具体实现（ActionNode / CompositeNode / ConditionNode / LoopNode / HumanNode）属于服务实现，独立成顶层类，归入 clara-workflow，不放 core、也不作为内部类。

### 4.5 planner（任务规划）

**接口 `ITaskPlanEngine`（`com.clara.core.planner`）**
- 作用：校验并执行任务 DAG，统一处理依赖调度、预算、重试、超时、幂等。
- 使用范围：任务规划执行；由 clara-workflow 实现。
- 为什么需要：core 定义执行契约，规划引擎实现与调用方解耦。

**接口 `ITaskHandler`（`com.clara.core.planner`）**
- 作用：执行单个任务节点。
- 使用范围：TaskNode 的处理器；由调用方实现。
- 为什么需要：任务逻辑与规划引擎解耦。

**接口 `IBudgetLedger`（`com.clara.core.planner`）**
- 作用：跟踪预算的预留、结算、释放与未知态。
- 使用范围：任务执行期的资源控制；由 clara-workflow 实现。
- 为什么需要：统一预算账本，控制总耗时 / 节点数 / 并发等资源；无租户。

**接口 `IExecutionLedger`（`com.clara.core.planner`）**
- 作用：跟踪任务执行的记录与状态。
- 使用范围：任务重试与幂等判定；由 clara-workflow 实现。
- 为什么需要：幂等键复用成功结果、未知副作用不自动重试的依据。

**接口 `ICapabilityAuthorizer`（`com.clara.core.planner`）**
- 作用：校验对能力的访问权限。
- 使用范围：计划校验时检查任务能力；由宿主或模块实现。
- 为什么需要：权限检查与规划逻辑解耦。

**DTO `TaskPlan`（`com.clara.dto.planner`）**
- 作用：描述一次执行的目标、版本、任务列表、约束与状态。
- 使用范围：规划引擎执行入参 / 结果。
- 为什么需要：任务规划的静态载体。

**DTO `TaskNode`（`com.clara.dto.planner`）**
- 作用：描述计划中单个任务的标识、依赖、能力、输出键、重试 / 超时 / 预算与处理器。
- 使用范围：TaskPlan 的任务元素。
- 为什么需要：任务 DAG 的节点载体。

**DTO `TaskPlanResult`（`com.clara.dto.planner`）**
- 作用：封装计划执行结果（计划、状态、各任务状态、输出、步数）。
- 使用范围：规划引擎执行返回值。
- 为什么需要：统一返回计划级执行结果。

**DTO `TaskExecutionContext`（`com.clara.dto.planner`）**
- 作用：向任务处理器提供计划、尝试次数、幂等键与可读值。
- 使用范围：ITaskHandler.execute 的入参。
- 为什么需要：单次任务尝试的上下文载体。

**DTO `TaskExecutionResult`（`com.clara.dto.planner`）**
- 作用：封装任务输出的变量映射与实际预算用量。
- 使用范围：ITaskHandler 的返回值。
- 为什么需要：任务级执行结果载体。

**DTO `ExecutionRecord`（`com.clara.dto.planner`）**
- 作用：任务副作用的持久化记录（幂等键、归属、状态、结果）。
- 使用范围：执行账本读写。
- 为什么需要：幂等与重试判定的依据。

**DTO `PlanLimits`（`com.clara.dto.planner`）**
- 作用：约束任务数、步骤数、重规划次数、并行度、总耗时与总预算。
- 使用范围：计划校验与执行期的资源上限。
- 为什么需要：资源控制的统一配置载体。

**DTO `RetryPolicy`（`com.clara.dto.planner`）**
- 作用：定义任务最大尝试次数与重试间隔。
- 使用范围：TaskNode 的重试策略。
- 为什么需要：重试策略的可配置载体。

**DTO `BudgetAmount`（`com.clara.dto.planner`）**
- 作用：以 token、成本、工作单元三维度计量预算额度。
- 使用范围：预算预留、结算、限制等场景。
- 为什么需要：预算的统一计量单位，支持加减与覆盖判断。

**DTO `BudgetSnapshot`（`com.clara.dto.planner`）**
- 作用：保存预算在特定时刻的限额、预留与已用额度。
- 使用范围：预算账本快照查询。
- 为什么需要：预算状态的只读快照。

**DTO `BudgetReservation`（`com.clara.dto.planner`）**
- 作用：记录一次任务执行的预算预留、实际使用与状态。
- 使用范围：预算账本的预留 / 结算 / 释放。
- 为什么需要：单次预留的状态载体。

**异常 `UnknownSideEffectException`（`com.clara.exception`）**
- 作用：标记任务副作用执行结果无法确定。
- 使用范围：任务执行超时 / 中断等无法确定结果时抛出。
- 为什么需要：区分「失败」与「未知」，未知副作用不自动重试。

**枚举 `PlanStatusEnum`（`com.clara.enums.planner`）**
- 作用：标识计划状态。
- 使用范围：TaskPlan / TaskPlanResult 的状态字段。
- 为什么需要：统一计划生命周期取值。

**枚举 `TaskNodeStatusEnum`（`com.clara.enums.planner`）**
- 作用：标识任务节点状态。
- 使用范围：TaskPlanResult 中各任务状态。
- 为什么需要：统一任务节点执行状态。

**枚举 `PlanActionEnum`（`com.clara.enums.planner`）**
- 作用：标识计划可执行的动作。
- 使用范围：计划执行期的动作分派。
- 为什么需要：统一动作取值。

**枚举 `BudgetReservationStatusEnum`（`com.clara.enums.planner`）**
- 作用：标识预算预留状态。
- 使用范围：BudgetReservation 的状态字段。
- 为什么需要：统一预留生命周期取值。

**枚举 `ExecutionRecordStatusEnum`（`com.clara.enums.planner`）**
- 作用：标识执行记录状态。
- 使用范围：ExecutionRecord 的状态字段。
- 为什么需要：统一执行记录状态（含 UNKNOWN 区分）。

### 4.6 interaction（交互，去 tenantId）

**接口 `IInteractionService`（`com.clara.core.interaction`）**
- 作用：人机交互的应用服务入口（列出待处理 / 获取 / 提交反馈 / 取消）。
- 使用范围：宿主或编排层触发交互；由 clara-workflow 实现，去 `tenantId` 参数。
- 为什么需要：统一交互生命周期管理入口，与工作流 / 任务恢复解耦。

**接口 `IUserInteractionGateway`（`com.clara.core.interaction`）**
- 作用：向用户发起交互请求的统一入口。
- 使用范围：工作流 / 任务进入等待输入时发起问题；由宿主或模块实现。
- 为什么需要：把「发起交互」与「如何呈现给用户」解耦。

**接口 `IAnswerSchemaValidator`（`com.clara.core.interaction`）**
- 作用：校验用户答案是否符合答案 Schema 约束。
- 使用范围：提交反馈时校验答案；由模块实现。
- 为什么需要：答案合法性校验与交互服务解耦。

**接口 `IInteractionResumeCoordinator`（`com.clara.core.interaction`）**
- 作用：协调交互恢复流程（恢复并记录，或标记取消）。
- 使用范围：反馈提交后恢复工作流 / 任务实例；由 clara-workflow 实现。
- 为什么需要：把「恢复实例」的编排与交互服务解耦。

**接口 `IWorkflowDefinitionResolver`（`com.clara.core.interaction`）**
- 作用：按实例标识解析工作流定义。
- 使用范围：恢复交互时加载定义；由宿主或模块实现。
- 为什么需要：定义加载与交互服务解耦。

**接口 `ITimeSource`（`com.clara.core.interaction`）**
- 作用：提供当前时间。
- 使用范围：交互创建 / 过期判断；由调用方实现。
- 为什么需要：时间可注入，便于过期判定与测试。

**DTO `InteractionContext`（`com.clara.dto.interaction`）**
- 作用：描述一次交互与运行 / 实例 / 触发节点之间的关联信息。
- 使用范围：创建交互时；去 `tenantId` 字段。
- 为什么需要：交互的关联与恢复定位依据，含乐观并发版本。

**DTO `InteractionFeedback`（`com.clara.dto.interaction`）**
- 作用：用户提交的交互反馈（答案、响应者、版本、幂等键）。
- 使用范围：提交反馈时作为入参；去 `tenantId` 字段。
- 为什么需要：反馈的输入载体，含幂等与并发控制字段。

**DTO `InteractionRequest`（`com.clara.dto.interaction`）**
- 作用：一次交互请求（上下文 + 问题 + 状态 + 响应）。
- 使用范围：交互服务内部状态载体。
- 为什么需要：交互请求的完整快照，支撑查询与状态迁移。

**DTO `InteractionResponse`（`com.clara.dto.interaction`）**
- 作用：一次交互的响应数据（答案、响应者、幂等键）。
- 使用范围：反馈提交的响应记录。
- 为什么需要：响应的结构化描述。

**DTO `Question`（`com.clara.dto.interaction`）**
- 作用：需要用户回答的交互问题（类型、文本、选项、Schema、默认答案等）。
- 使用范围：发起交互时定义问题。
- 为什么需要：问题的结构化描述，含选项唯一性与必填校验。

**DTO `InteractionOption`（`com.clara.dto.interaction`）**
- 作用：选择题的单个选项（选项 ID + 标签）。
- 使用范围：Question 的选项列表。
- 为什么需要：选择类问题的选项载体。

**DTO `FeedbackResult`（`com.clara.dto.interaction`）**
- 作用：反馈操作的执行结果（请求、是否幂等重放、恢复结果）。
- 使用范围：反馈提交的返回值。
- 为什么需要：区分正常提交与幂等重放。

**DTO `ResumeOutcome`（`com.clara.dto.interaction`）**
- 作用：恢复的处理结果（实例状态 + 版本）。
- 使用范围：反馈提交后恢复实例的返回。
- 为什么需要：恢复结果的结构化描述。

**枚举 `InteractionTypeEnum`（`com.clara.enums.interaction`）**
- 作用：标识交互类型（文本 / 单选 / 多选 / 确认 / 文件 / 资源上传 / 变更审批）。
- 使用范围：Question 的类型字段。
- 为什么需要：约束交互类型取值，驱动问题渲染与校验。

**枚举 `InteractionStateEnum`（`com.clara.enums.interaction`）**
- 作用：标识交互状态（待处理 / 已答 / 取消 / 过期 / 被替代）。
- 使用范围：InteractionRequest 的状态字段。
- 为什么需要：约束交互生命周期状态。

> planner 的 `PlanValidator`、`TaskPlanReplanner` 等按「core 定义接口、workflow 实现」原则拆分，具体签名在 clara-workflow 技术设计中细化。

## 5. 无租户（去 tenantId）方案

- `clara-memory`：`MemoryEntry` / `SemanticMemoryQuery` / `ISemanticMemoryStore.deleteByConversation` 去掉 `tenantId`，语义记忆仅按会话 ID 关联；
- `clara-workflow`：`planner.IBudgetLedger` 去掉 `tenantSnapshot()`；`interaction` 的 `InteractionContext` / `InteractionFeedback` / `IInteractionService` 去掉 `tenantId` 字段与参数；
- `clara-application` / `clara-orchestration`：后续阶段重写时去除；
- `clara-core` 与 `clara-model` 现有 `tenantId`（媒体任务、凭据解析等非业务会话场景）保留不动。

## 6. 实施顺序

### 步骤 1：落地 core 契约（接口 / DTO / 枚举）

- 目标：按第 3 节契约清单定义全部接口（`com.clara.core.*`）、DTO（`com.clara.dto.*`）、枚举（`com.clara.enums.*`）。
- 内容：接口以 `I` 前缀、枚举以 `Enum` 结尾放对应子包、DTO 放 `com.clara.dto.<domain>`；core 只放接口与公共属性，不放服务实现。
- 前置：无（core 不依赖其他模块）。
- 验证：`mvn -pl clara-core compile` 编译通过。

### 步骤 2：新建 clara-workflow（迁出 + 接口化 + 去租户）

- 目标：从 clara-orchestration 迁出 workflow / planner / interaction，接口化并去租户。
- 内容：`DefaultWorkflowEngine`（实现 IWorkflowEngine）、`DefaultTaskPlanEngine`（实现 ITaskPlanEngine）、`InMemoryCheckpointStore`（实现 ICheckpointStore）、`InMemoryBudgetLedger`、`InMemoryExecutionLedger`、`InMemoryInteractionService`、`PlanValidator`、`TaskPlanReplanner` 等；节点实现（ActionNode / CompositeNode / ConditionNode / LoopNode / HumanNode）独立成顶层类。
- 前置：步骤 1（依赖 core 契约）。
- 验证：`mvn -pl clara-workflow -am compile` 编译通过。

### 步骤 3：重写 clara-memory（窗口 / 压缩 / 语义记忆 / 持久化）

- 目标：实现窗口记忆、压缩编排、语义记忆（Local / Milvus）、会话持久化。
- 内容：`DefaultWindowMemory`（实现 IWindowMemory）、`DefaultSummarizeCoordinator`（实现 ISummarizeCoordinator）、`LocalSemanticMemoryStore` / `MilvusSemanticMemoryStore`（实现 ISemanticMemoryStore）、会话持久化实现。
- 前置：步骤 1（依赖 core 契约）。
- 验证：编译 + 测试通过。

### 步骤 4：重写 clara-skill（技能 / 提示词 / 工具脚本执行）

- 目标：实现技能目录扫描、热加载、提示词管理、工具方法脚本执行（Python / JavaScript / Shell）。
- 内容：`DefaultPromptProvider`（实现 IPromptProvider）、`DefaultToolInvoker`（实现 IToolInvoker）、`DefaultToolExecutor`（实现 IToolExecutor）、脚本执行器（Python / JavaScript / Shell）与执行器注册表、技能注册表与执行器。
- 前置：步骤 1（依赖 core 契约）。
- 验证：编译 + 测试通过。

### 步骤 5：clara-core 新增 control 配置 + clara-model 落地嵌入 / token 估算实现

- 目标：在 clara-core 改造 `ProviderCapabilityConfiguration` 新增 `control` 配置类（`com.clara.kernel.config.ContextControlConfiguration`），承载窗口 / 压缩 / 向量化配置；并在 clara-model 落地 `IEmbeddingClient`（嵌入 API 适配）与 `ITokenCountEstimator`（token 估算，按模型 / 平台区分）实现。
- 内容：`control` 节点与模型类型同级别，含 `contextWindowTokens`、`windowMaxMessages`、`windowMaxSizeBytes`、`windowMaxTokensPercent`、压缩开关、向量化开关、检索条数与相似度阈值；`IEmbeddingClient` 对接 DeepSeek / 火山方舟 / Kimi 等嵌入 API；`ITokenCountEstimator` 按模型 / 平台提供 token 估算。
- 前置：步骤 1。
- 验证：编译 + 测试通过。

### 步骤 6：全量编译验证

- 目标：确保整个 reactor 编译 + 测试通过。
- 验证：`mvn verify`。
