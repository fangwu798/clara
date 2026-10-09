# clara-workflow 服务 — 技术设计文档

> 状态：已审核
> 前置：`clara-workflow-service-business-design.md`（已审核）、`clara-core-contract-technical-design.md`（已审核）

## 1. 目标

从 clara-orchestration 拆出工作流、任务规划、交互三类能力，形成独立的 clara-workflow 模块，实现 core 定义的接口。现有代码仅作参考、按新业务重写、不做兼容。

命名规范与包结构约束以 `clara-core-contract-technical-design.md` 为准：接口 / DTO / 枚举已在 core 定义，本模块只提供 `com.clara.workflow.*` 下的实现类；节点实现（ActionNode 等）独立成顶层类、不作为内部类。

## 2. 模块依赖

- `clara-core`：实现其 workflow / planner / interaction 契约；
- `clara-mcp`：工作流节点可调用外部 mcp 服务进行联动（业务决策 WD2）。

## 3. 文件结构（要创建的文件）

```
clara-workflow/src/main/java/com/clara/workflow/
├── engine/
│   ├── DefaultWorkflowEngine.java          # 实现 IWorkflowEngine
│   └── WorkflowErrors.java                 # 工作流领域错误
├── node/
│   ├── WorkflowNodes.java                  # 节点构造工厂
│   ├── ActionNode.java                     # AGENT / TOOL 动作节点
│   ├── CompositeNode.java                  # SEQUENCE / PARALLEL 组合节点
│   ├── ConditionNode.java                  # 条件分支节点
│   ├── LoopNode.java                       # 循环节点
│   └── HumanNode.java                      # 人工输入节点
├── checkpoint/
│   └── InMemoryCheckpointStore.java        # 实现 ICheckpointStore（内存）
├── planner/
│   ├── DefaultTaskPlanEngine.java          # 实现 ITaskPlanEngine
│   ├── PlanValidator.java                  # 计划校验
│   ├── TaskPlanReplanner.java              # 计划重规划
│   ├── InMemoryBudgetLedger.java           # 实现 IBudgetLedger
│   ├── InMemoryExecutionLedger.java        # 实现 IExecutionLedger
│   └── PlannerErrors.java                  # 规划领域错误
└── interaction/
    ├── InMemoryInteractionService.java     # 实现 IInteractionService
    ├── InteractionResumeCoordinator.java   # 实现 IInteractionResumeCoordinator（工作流交互恢复协调）
    ├── AnswerSchemaValidator.java          # 实现 IAnswerSchemaValidator
    ├── UserInteractionGateway.java         # 实现 IUserInteractionGateway
    └── InteractionErrors.java              # 交互领域错误
```

## 4. 实现类清单（每个类含作用 / 使用范围 / 为什么需要）

### 4.1 工作流引擎与节点

**`DefaultWorkflowEngine`（`com.clara.workflow.engine`，实现 `IWorkflowEngine`）**
- 作用：执行确定性工作流，支持暂停 / 人工输入 / 恢复 / 取消。
- 使用范围：对话编排调用工作流；持有 `ICheckpointStore` 与并行执行线程池。
- 为什么需要：core 定义执行契约，这里落地 DAG 调度、检查点与并行分支执行。

**`WorkflowNodes`（`com.clara.workflow.node`）**
- 作用：提供六类节点的构造入口（动作 / 顺序 / 并行 / 条件 / 循环 / 人工）。
- 使用范围：构建 `WorkflowDefinition` 时使用。
- 为什么需要：统一节点构造入口，避免调用方直接接触节点实现类。

**`ActionNode`（`com.clara.workflow.node`，实现 `IWorkflowNode`）**
- 作用：AGENT / TOOL 动作节点，承载 `IWorkflowAction` 与幂等键。
- 使用范围：工作流中的执行动作节点。
- 为什么需要：落地动作节点的结构，供引擎按类型分派执行。

**`CompositeNode`（`com.clara.workflow.node`，实现 `IWorkflowNode`）**
- 作用：SEQUENCE / PARALLEL 组合节点，承载子节点列表。
- 使用范围：顺序或并行组织子节点。
- 为什么需要：落地组合节点的结构，支持顺序与并行两种调度。

**`ConditionNode`（`com.clara.workflow.node`，实现 `IWorkflowNode`）**
- 作用：条件分支节点，承载 `IScopeCondition` 与真假分支。
- 使用范围：按条件选择分支执行。
- 为什么需要：落地条件节点的结构，供引擎求值并分派分支。

**`LoopNode`（`com.clara.workflow.node`，实现 `IWorkflowNode`）**
- 作用：循环节点，承载循环体、最大迭代次数与退出条件。
- 使用范围：重复执行循环体直至条件满足或达到上限。
- 为什么需要：落地循环节点的结构，供引擎迭代执行。

**`HumanNode`（`com.clara.workflow.node`，实现 `IWorkflowNode`）**
- 作用：人工输入节点，承载输入键。
- 使用范围：工作流等待人工输入时。
- 为什么需要：落地人工输入节点的结构，触发 WAITING_INPUT 暂停。

**`WorkflowErrors`（`com.clara.workflow.engine`）**
- 作用：集中创建工作流领域错误。
- 使用范围：引擎内部。
- 为什么需要：统一错误码与分类。

### 4.2 检查点

**`InMemoryCheckpointStore`（`com.clara.workflow.checkpoint`，实现 `ICheckpointStore`）**
- 作用：在内存中保存并读取工作流检查点。
- 使用范围：测试或单进程临时任务。
- 为什么需要：提供无外部依赖的检查点实现，生产环境可替换为持久化实现。

### 4.3 任务规划

**`DefaultTaskPlanEngine`（`com.clara.workflow.planner`，实现 `ITaskPlanEngine`）**
- 作用：校验并执行任务 DAG，统一处理依赖调度、预算、重试、超时、幂等。
- 使用范围：任务规划执行；依赖 `PlanValidator` + `IBudgetLedger` + `IExecutionLedger`。
- 为什么需要：core 定义执行契约，这里落地计划调度与资源控制。

**`PlanValidator`（`com.clara.workflow.planner`）**
- 作用：校验计划（任务数、输出键 / 幂等键唯一、依赖合法、无环、预算与能力）。
- 使用范围：计划执行前。
- 为什么需要：保证计划合法性，执行前拦截非法计划。

**`TaskPlanReplanner`（`com.clara.workflow.planner`）**
- 作用：校验并生成重规划计划（保持已完成任务幂等键 / 输出键 / 能力一致）。
- 使用范围：运行中计划需要修订时。
- 为什么需要：支持受限的计划修订，避免破坏已完成任务。

**`InMemoryBudgetLedger`（`com.clara.workflow.planner`，实现 `IBudgetLedger`）**
- 作用：在内存中跟踪预算的预留、结算、释放与未知态。
- 使用范围：单进程任务规划。
- 为什么需要：落地预算账本，无租户、仅按 `runId` 维度。

**`InMemoryExecutionLedger`（`com.clara.workflow.planner`，实现 `IExecutionLedger`）**
- 作用：在内存中跟踪任务执行记录与状态。
- 使用范围：单进程任务规划。
- 为什么需要：落地执行账本，支撑幂等与重试判定。

**`PlannerErrors`（`com.clara.workflow.planner`）**
- 作用：集中创建规划领域错误。
- 使用范围：规划引擎内部。
- 为什么需要：统一错误码与分类。

### 4.4 交互

**`InMemoryInteractionService`（`com.clara.workflow.interaction`，实现 `IInteractionService`）**
- 作用：在内存中管理待处理交互（列出 / 获取 / 提交反馈 / 取消）。
- 使用范围：单进程交互管理，按 `runId` 维度。
- 为什么需要：落地交互生命周期管理，去租户。

**`InteractionResumeCoordinator`（`com.clara.workflow.interaction`，实现 `IInteractionResumeCoordinator`）**
- 作用：协调交互恢复与取消的通用流程（校验版本、解析工作流定义、调用工作流引擎恢复、记录结果）。
- 使用范围：反馈提交 / 交互取消时。
- 为什么需要：core 定义契约，这里把「交互反馈 → 工作流恢复」串起来并记录结果。

**`AnswerSchemaValidator`（`com.clara.workflow.interaction`，实现 `IAnswerSchemaValidator`）**
- 作用：校验用户答案是否符合 Schema 约束。
- 使用范围：提交反馈时。
- 为什么需要：答案合法性校验。

**`UserInteractionGateway`（`com.clara.workflow.interaction`，实现 `IUserInteractionGateway`）**
- 作用：向用户发起交互请求。
- 使用范围：工作流 / 任务进入等待输入时。
- 为什么需要：把「发起交互」与「呈现给用户」解耦。

**`InteractionErrors`（`com.clara.workflow.interaction`）**
- 作用：集中创建交互领域错误。
- 使用范围：交互实现内部。
- 为什么需要：统一错误码与分类。

## 5. 关键实现细节

### 5.1 工作流引擎（`DefaultWorkflowEngine`）

**定义与校验**：`WorkflowDefinition` 承载标识 / 版本 / Schema / 根节点；构造期校验无环、节点 ID 唯一，非法定义在启动即被拒绝（业务规则 WR1）。

**节点分派**：按 `WorkflowNodeTypeEnum` 分派执行——
- `ACTION`（AGENT / TOOL）：执行 `IWorkflowAction`，传入当前 `ExecutionScope`，读取作用域变量、回写输出键；
- `SEQUENCE`：按顺序遍历子节点，前序完成后执行后续；
- `PARALLEL`：并发执行子节点，经并行线程池调度，全部完成后汇合；
- `CONDITION`：求值 `IScopeCondition`，选择真 / 假分支；
- `LOOP`：重复执行循环体，直至 `IScopeCondition` 退出条件满足或达到 `maxIterations`；
- `HUMAN`：缺输入时抛出等待信号、进入 `WAITING_INPUT` 暂停并落检查点。

**执行状态与作用域**：执行期累计变量、节点结果、完成标识与控制状态到 `ExecutionScope`；实例状态由 `WorkflowInstanceStatusEnum` 描述（含终态判断）。

**检查点与恢复**：暂停 / 恢复 / 取消均经 `ICheckpointStore` 读写 `WorkflowCheckpoint`（实例 / 工作流标识、版本、作用域）；恢复时校验实例版本，防止基于旧状态覆盖（WR2）。

**资源释放**：持有并行执行线程池，`close()` 释放线程；取消实例时中断正在执行的节点。

### 5.2 任务规划引擎（`DefaultTaskPlanEngine`）

**依赖**：`PlanValidator` + `IBudgetLedger` + `IExecutionLedger` + `ICapabilityAuthorizer`；任务节点执行依赖 `ITaskHandler`（由调用方实现）；`ICapabilityAuthorizer` 由宿主注入。

**计划校验（`PlanValidator`）**：执行前校验任务数、输出键 / 幂等键唯一、依赖合法、无环、预算与能力。

**依赖调度**：按 DAG 拓扑序调度，前驱任务全部完成后任务才可执行；并行度受 `PlanLimits` 约束。

**预算控制（`IBudgetLedger`）**：以 `BudgetAmount`（token / 成本 / 工作单元三维度）计量，执行前预留、成功后结算、失败释放、无法确定结果时标记未知态；`PlanLimits` 统一约束任务数、步骤数、并行度、总预算、总耗时。

**重试 / 超时**：按 `TaskNode` 的 `RetryPolicy`（最大尝试次数、重试间隔）重试；超时中断；结果无法确定时抛 `UnknownSideEffectException`。

**执行幂等（`IExecutionLedger`）**：以幂等键写入 `ExecutionRecord`（状态由 `ExecutionRecordStatusEnum` 描述，区分成功 / 失败 / UNKNOWN）；相同幂等键复用成功结果，未知副作用不自动重试。

**重规划（`TaskPlanReplanner`）**：运行中修订计划时，保持已完成任务的幂等键 / 输出键 / 能力一致，避免破坏已完成结果。

### 5.3 交互（`InMemoryInteractionService`）

**交互管理**：按 `runId` 管理待处理交互；`Question` 描述问题类型（`InteractionTypeEnum`）、文本、选项、答案 Schema 与默认答案。

**发起交互（`IUserInteractionGateway`）**：工作流 / 任务进入等待输入时发起问题，交互状态由 `InteractionStateEnum` 描述（待处理 / 已答 / 取消 / 过期 / 被替代）。

**提交反馈**：`IAnswerSchemaValidator` 校验答案合法性；校验 `expectedInstanceVersion` 与幂等键，防止重复提交或基于旧状态覆盖（WR2）。

**恢复执行（`IInteractionResumeCoordinator`）**：经 `IWorkflowDefinitionResolver` 解析工作流定义，调用引擎恢复实例并记录结果；`FeedbackResult` 区分正常提交与幂等重放。

**过期处理**：时间经 `ITimeSource` 注入，过期交互标记为过期。
