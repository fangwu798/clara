# 多角色协作与动态编排 — 技术设计文档

> 状态：待审核
> 前置：`clara-core-contract-technical-design.md`（已审核）、`clara-workflow-service-technical-design.md`（已审核）、`clara-orchestration-service-technical-design.md`（已审核）
> 本文分析「工作流不固定 + 多角色（同 / 异模型）+ 多角色审核讨论 + 过程中调用图片识别 / 图片生成 / 视频生成」这类业务场景的落地路径，并给出分期技术方案。命名与包结构约束以 `clara-core-contract-technical-design.md` 为准。
>
> 修订（2026-10-15）：§4.3 / §4.7 的类分工已收敛——`ModelBinding` 与 `ModelSession` 均并入 `ModelRegistry`（新增「会话当前模型」的选择与读取），`ModelRegistry` 迁至 `com.clara.config`（与 `ConfigLocator` 同层）；下文保留当时的设计记录，实际包名与类型以现结构为准。

## 1. 目标与场景

用户在对话中输入一段描述后，期望系统现场决定「做什么、由谁做」：

1. **工作流不固定**：执行流程由用户描述现场决定，而非预先固化的 YAML；
2. **多角色分工**：同一任务拆给多个角色（架构师 / 评审员 / 测试员…），角色可能由**同一模型**担任，也可能由**不同模型**担任；
3. **多角色协作**：角色之间需要互相审核、多轮讨论，最终收敛出结论；
4. **多模态参与**：协作过程中可能调用**图片识别**（图 → 文）、**图片生成**（文 → 图）、**视频生成**（文 → 视频），产物需要落成可引用的资产。

本文的目标是把上述场景拆成可独立交付、可验证的四个阶段，并给出**阶段一**的完整技术方案（含类 / 接口 / 配置改动清单），其余阶段给出方案轮廓与改动方向。

## 2. 现状分析（能力与差距）

### 2.1 已具备的能力

| 能力 | 现状依据 |
| --- | --- |
| 工作流引擎与节点 | `DefaultWorkflowEngine` 支持 SEQUENCE / PARALLEL / CONDITION / LOOP / AGENT / TOOL / HUMAN；并行分支经 `ExecutionScope.merge` 汇合变量 |
| 配置化工作流 | `WorkflowDefinitionRegistry.reload()` 递归加载 `config/workflow/**/*.yml`；`WorkflowDefinitionLoader` 把 YAML 节点解析为 `WorkflowActionSpec` 交由 `IWorkflowActionFactory` 创建动作 |
| 动作工厂 SPI | `AgentActionFactory`（AGENT）、`ToolActionFactory`（TOOL）按 `kind` 命中；动作节点必须且只能声明一个 `outputKeys` |
| 逐请求多模型路由 | `ConversationRequest` 已含 `provider` / `model` / `capabilityKind` / `providerConfiguration`；编排器按请求参数路由，**运行期能力已具备** |
| 能力分派 | `CapabilityDispatcher` 按 `capabilityKind` 分派；`ProviderConfiguration.toConfig(CapabilityKindEnum)` 是唯一转换出口 |
| 多模态能力枚举 | `CapabilityKindEnum` 含 `CHAT` / `IMAGE` / `VIDEO` / `AUDIO_TRANSCRIPTION` / `SPEECH` |
| 工具链 | `IToolInvoker` / `ScriptExecutorRegistry` / Python 自描述工具；`RegistryToolCatalog` 全量下发工具 |
| 媒体与交互组件 | `MediaStore`（文件 / 对象存储）、`MediaJobScheduler`、回调校验、`InMemoryInteractionService`、`InteractionResumeCoordinator` 均已实现 |

### 2.2 差距清单

| # | 差距 | 影响 | 关键位置 |
| --- | --- | --- | --- |
| G1 | AGENT 节点**只读 `prompt` / `promptVars`**，节点上的 `model` / `role` 等被静默忽略 | 无法按节点选不同模型 → 多角色异模型不可达 | `AgentActionFactory` |
| G2 | `AgentWorkflowAction` **硬编码 `CapabilityKindEnum.CHAT`** | 无法经 AGENT 节点触发图片 / 视频能力 | `AgentWorkflowAction.execute` |
| G3 | 全流程**只有一份** `ProviderConfiguration`（`WorkflowModelContext` 单份 volatile） | 同一工作流所有 AGENT 节点共用同一模型 | `WorkflowModelContext`、`ApplicationBootstrap`、`ModelSession` |
| G4 | 配置化加载器**显式拒绝 CONDITION / LOOP** | 无法用 YAML 表达「多轮讨论」循环 | `WorkflowDefinitionLoader.buildNode` |
| G5 | `WorkflowDefinitionRegistry` **只有文件目录加载**，无运行期注册 / 覆盖 API | 「描述 → 现场生成工作流」无处落位 | `WorkflowDefinitionRegistry` |
| G6 | **无「用户输入 → 工作流定义」生产者**（planner 引擎为孤岛，`TaskPlan` 需外部构造） | 工作流固定，无法现场编排 | `clara-workflow/planner` |
| G7 | `ConversationRequest` **无附件 / 多模态输入载体**；`CapabilityKindEnum` 无 `VISION` | 图片识别（图 → 文）无载体 | `ConversationRequest`、`CapabilityKindEnum` |
| G8 | `CompatibleConfigResolver` **仅覆盖 CHAT + IMAGE** | VISION / VIDEO 等能力解析缺位 | `CompatibleConfigResolver` |
| G9 | 媒体子系统（`MediaStore` / `MediaJobScheduler`）**未接入 `ApplicationBootstrap`** | 图片 / 视频产物无法资产化 | `ApplicationBootstrap` |
| G10 | `IWorkflowDefinitionResolver` **以 null 注入**，`WorkflowRunner` **无 resume 入口** | HUMAN 节点等待 / 恢复未打通 | `ApplicationBootstrap`、`WorkflowRunner` |

> 结论：**「多角色 / 多模型」的技术底座（逐请求路由、动作工厂 SPI、并行汇合）已具备，缺的是「按节点绑定模型」这一环（G1–G3）**。因此阶段一只需打通 G1–G3，即可支撑「多角色同 / 异模型」的最小业务闭环。

## 3. 总体方案与阶段划分

| 阶段 | 主题 | 覆盖差距 | 交付判据 |
| --- | --- | --- | --- |
| 一 | AGENT 节点按「角色 / 模型 / 能力」绑定 | G1、G2、G3 | 一份 YAML 中不同 AGENT 节点可用不同模型、不同能力，且实际调用到各自模型 |
| 二 | 多角色审核与讨论 | G1–G4 | 一份 YAML 可声明「N 角色 × M 轮讨论 + 汇总」，产出结论 |
| 三 | 多模态（图片识别 / 图片生成 / 视频生成） | G2、G7、G8、G9 | AGENT 节点可消费图片、可产出图片 / 视频资产并回填 scope |
| 四 | 动态编排（描述 → 工作流）+ 人机交互打通 | G5、G6、G10 | 用户描述可现场生成并运行工作流；HUMAN 节点可等待并恢复 |

阶段一、二只动 application / clara-workflow / 配置，不引入外部依赖；阶段三、四涉及 core 契约与媒体接线，需单独评审。

## 4. 阶段一：AGENT 节点按「角色 / 模型 / 能力」绑定（详细技术方案）

### 4.1 目标

让每个 AGENT 节点能独立指定「用哪个模型、用哪种能力」，从而支撑「多角色可能是相同模型，也可以是不同模型」。

### 4.2 属性约定（YAML）

AGENT 节点在既有 `prompt` / `promptVars` 之外，新增**可选**属性：

| 属性 | 类型 | 缺省 | 说明 |
| --- | --- | --- | --- |
| `role` | string | 无 | 逻辑角色名（自由命名，如 `architect` / `reviewer`）。经**角色表**解析为模型别名；角色表未配置时，`role` 直接作为模型别名 |
| `model` | string | 无 | 模型别名，直接指定，**优先于 `role`** |
| `capability` | string | `CHAT` | 能力种类名（`CapabilityKindEnum` 常量名） |

**解析优先级**：`model` 显式指定 > `role`（角色表 → 别名，或直作别名）> 会话默认绑定。

> 取舍：**不新增 `provider` 属性**。平台（provider）已由所选 `ProviderConfiguration` 携带（`configuration.getProvider()`），再单独暴露会与配置产生二义性；需要区分平台时，用不同别名（不同配置文件）表达即可。

示例（节选）：

```yaml
- id: design
  type: AGENT
  outputKeys: [design_doc]
  role: architect          # 角色：架构师（由角色表选到不同模型）
  prompt: solution_architect
  promptVars: { goal: "${goal}", sources: "${sources}" }

- id: generate_cover
  type: AGENT
  outputKeys: [cover_asset]
  model: volcengine-image  # 直接指定模型别名
  capability: IMAGE        # 走图片生成能力
  prompt: cover_designer
  promptVars: { title: "${title}" }
```

### 4.3 新增 / 改动类清单

**A. 新增 `com.clara.session.ModelRegistry`（application）**

模型绑定注册表：按别名管理多份 `ProviderConfiguration` 与可选的角色表，是 G3 的解法。

- 职责：别名 → `ProviderConfiguration` + 默认模型；角色名 → 别名；生命周期归本类所有。
- 关键签名：

```java
public final class ModelRegistry implements Closeable {
    /** 绑定会话默认别名（等价于 ModelSession 当前生效模型）。 */
    public void bindDefault(String alias);
    /** 解析 AGENT 节点应使用的模型绑定；model 优先、其次 role、最后默认。 */
    public ModelBinding resolve(String role, String modelAlias);
    /** 已注册别名；供 /model 列表与错误提示使用。 */
    public Set<String> aliases();
    /** 关闭全部已加载的平台配置。 */
    @Override public void close();
}
```

- 加载语义：**按需惰性加载**——首次 `resolve` 命中别名时，从 `config/model-providers/**`（经 `ConfigLocator` 定位）加载该别名的 `*.yml` 并缓存；加载失败抛 `IllegalArgumentException`（消息含别名与可用别名列表）。惰性加载避免启动期加载全部配置及其失败耦合。
- 别名规则：**模型配置文件名去掉 `yml` / `yaml` 扩展名**（如 `deepseek`、`volcengine-doubao`），与 `ConfigLocator.resolveModel` 的匹配口径一致。

**B. 新增 `com.clara.session.ModelBinding`（application，顶层值对象）**

`resolve` 的返回：`ProviderConfiguration configuration`（非空）+ `String model`（可空，空表示退回该配置对应能力的默认模型 id）。命名字段 + 只读访问器。

**C. 新增 `com.clara.workflow.application.AgentNodeOptions`（application，顶层值对象）**

从 `WorkflowActionSpec` 解析并校验 AGENT 节点的可选属性，集中解析逻辑、避免工厂内散落字符串：

```java
public final class AgentNodeOptions {
    public static AgentNodeOptions fromSpec(WorkflowActionSpec spec);
    public String role();               // 可空
    public String modelAlias();         // 可空
    public CapabilityKindEnum capability();  // 非空，缺省 CHAT
}
```

- 校验：`capability` 非 `CapabilityKindEnum` 合法值时抛 `IllegalArgumentException`（消息含节点 id 与合法取值）。

**D. 改动 `com.clara.workflow.application.AgentWorkflowAction`**

- 依赖由 `WorkflowModelContext` 换成 `ModelRegistry`；构造新增 `AgentNodeOptions`。
- `execute` 内：
  1. `ModelBinding binding = modelRegistry.resolve(options.role(), options.modelAlias());`
  2. 以 `options.capability()` 校验能力已配置（`configuration.getCapability(capability)` 不存在即 fail-fast）；
  3. `model` 为空时退回 `configuration.toConfig(capability).getModelId()`；
  4. 组装 `ConversationRequest`：`provider(configuration.getProvider())` + `model(model)` + **`capabilityKind(capability)`**（替换当前硬编码 CHAT）+ `providerConfiguration(configuration)`。
- `conversationId` 保持 `wf:{instanceId}:{nodeId}`；有 `role` 时可选拼为 `wf:{instanceId}:{role}:{nodeId}`，用于记忆 / 日志按角色隔离。

**E. 改动 `com.clara.workflow.application.AgentActionFactory`**

- 构造依赖由 `WorkflowModelContext` 换成 `ModelRegistry`。
- `create` 内解析 `AgentNodeOptions.fromSpec(spec)` 并传入 `AgentWorkflowAction`；`prompt` 仍为必填。

**F. 删除 `com.clara.workflow.application.WorkflowModelContext`**

职责被 `ModelRegistry` 取代（单份绑定 → 命名多份绑定 + 默认绑定）。同步更新 `ApplicationBootstrap.workflowModelContext()` 的暴露方式与 `ModelSession` 的写入点。

**G. 改动 `com.clara.session.ModelSession`**

- 由「持有一份配置并负责 `close`」改为「持有 `ModelRegistry` + 当前别名」；`switchTo` 变为切换默认别名（不再重复加载 / 关闭）。
- 模型配置的生命周期统一由 `ModelRegistry.close()` 负责，消除多处 ownership 歧义。

**H. 改动 `com.clara.ApplicationBootstrap`**

- 构造 `ModelRegistry` 并注入 `AgentActionFactory`；`modelSession` 建立后调用 `registry.bindDefault(defaultAlias)`。
- `workflowModelContext()` 访问器替换为 `modelRegistry()`。
- `close()` 增加 `modelRegistry.close()`。

### 4.4 配置改动

| 配置 | 改动 | 必需 |
| --- | --- | --- |
| `config/model-providers/**/*.yml` | 无需改动；别名即文件名 | — |
| `config/clara.yml` | `defaultModel` 语义不变，值改为**别名**（与文件名口径一致） | — |
| `config/model-roles.yml`（新增，可选） | 角色 → 别名的映射，例如 `architect: deepseek` / `reviewer: volcengine-doubao`；不配置时角色名直接作别名 | 可选 |
| `config/workflow/**.yml` | AGENT 节点按需补充 `role` / `model` / `capability` | 按需 |

### 4.5 关键流程

```
WorkflowDefinitionLoader 解析 YAML 节点
        │  attributes(prompt/promptVars/role/model/capability)
        ▼
AgentActionFactory.create(spec) ── AgentNodeOptions.fromSpec ──▶ AgentWorkflowAction
        ▼
AgentWorkflowAction.execute(scope)
        │  ModelRegistry.resolve(role, modelAlias) ──▶ ModelBinding(configuration, model)
        │  capability = options.capability(); 校验 configuration.getCapability(capability)
        ▼
DefaultConversationOrchestrator.converse(ConversationRequest{provider, model, capabilityKind, providerConfiguration})
        ▼
CapabilityDispatcher 按 capabilityKind 分派 CHAT / IMAGE / ...
```

### 4.6 验证

1. `mvn -pl clara-application -am test` 全绿；
2. 新增单测：同一 YAML 中两个 AGENT 节点分别绑定不同别名，断言 `ConversationRequest` 收到各自的 `provider` / `model` / `capabilityKind`；
3. 新增单测：`role` 经角色表解析、`model` 覆盖 `role`、未注册别名 fail-fast；
4. 端到端：以 `capability: IMAGE` 的 AGENT 节点触发图片能力（当前 `CompatibleConfigResolver` 已支持 IMAGE）。

### 4.7 阶段一改动清单汇总

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `com/clara/session/ModelRegistry.java` | 新增 | 命名模型绑定注册表 + 生命周期 |
| `com/clara/session/ModelBinding.java` | 新增 | 模型绑定值对象 |
| `com/clara/workflow/application/AgentNodeOptions.java` | 新增 | AGENT 节点属性解析与校验 |
| `com/clara/workflow/application/AgentWorkflowAction.java` | 改动 | 按节点注入 provider / model / capability |
| `com/clara/workflow/application/AgentActionFactory.java` | 改动 | 依赖换为 `ModelRegistry`，解析 `AgentNodeOptions` |
| `com/clara/workflow/application/WorkflowModelContext.java` | 删除 | 职责并入 `ModelRegistry` |
| `com/clara/session/ModelSession.java` | 改动 | 改为默认别名选择器 |
| `com/clara/ApplicationBootstrap.java` | 改动 | 装配 `ModelRegistry`、替换访问器、纳入 `close()` |
| `config/model-roles.yml` | 新增（可选） | 角色 → 别名映射 |

> `WorkflowActionSpec` 无需改结构：其属性已是 `Map` 透传，`getAttribute(name)` 可取到新增属性；如需可读性可补 `getStringAttribute` / `getMapAttribute` 便利方法（可选）。

## 5. 阶段二：多角色审核与讨论

### 5.1 目标

一份 YAML 声明「N 个角色 × M 轮讨论 + 汇总」，角色间互相可见对方意见，最终收敛出结论。

### 5.2 方案

**MVP（纯 YAML，零引擎改动）**：用既有 SEQUENCE + PARALLEL 表达固定流程。
`review_panel`(PARALLEL){ `reviewer_a`(AGENT, role=…) ∥ `reviewer_b` ∥ `reviewer_c` } → `moderate`(AGENT, role=moderator) 读取三者产出 → `verdict`。PARALLEL 分支变量经 `ExecutionScope.merge` 汇合，已支持。

**增强（新增 `REVIEW` 动作型节点）**：当需要**多轮**讨论（每轮角色看到前轮全部发言）时，YAML 需要循环能力，而当前加载器拒绝 CONDITION / LOOP（G4）。为避免改动引擎分派，采用「**REVIEW 作为动作节点**」：

- `WorkflowNodeTypeEnum` 新增 `REVIEW`（枚举，core）；
- `WorkflowDefinitionLoader` 中 `case REVIEW` 走 `buildActionNode`（与 AGENT / TOOL 同路径，单输出键）；
- 新增 `ReviewActionFactory`（`supports("REVIEW")`）+ `ReviewWorkflowAction`：
  - 属性：`roles`（角色数组）、`topic`、`rounds`（轮次）、`moderator`（汇总角色）、`prompt`；
  - 执行：顺序让每个角色基于「累积讨论稿 `${discussion}`」发言并追加 → 末轮由 `moderator` 汇总为结论输出；
  - 复用 `ModelRegistry` 为各角色选模型（阶段一成果）。
- 引擎无需改动：REVIEW 复用「动作节点 + 输出键」形态。

> 取舍：不开启 CONDITION / LOOP 的配置化加载，避免为「多轮讨论」单独引入作用域条件求值的 DSL；讨论轮转收敛在 `ReviewWorkflowAction` 一处实现，更可控。

## 6. 阶段三：多模态（图片识别 / 图片生成 / 视频生成）

| 方向 | 改动 |
| --- | --- |
| 图片识别（图 → 文） | `CapabilityKindEnum` 新增 `VISION`（core 枚举）；`CompatibleConfigResolver` 覆盖 VOLCENGINE / ALIBABA / KIMI 的 `VISION`（新增 `VisionCapabilityConfig`）；`ConversationRequest` 新增 `List<ConversationAttachment>`（core DTO），AGENT 节点新增 `attachments` 属性，动作把变量中的图片路径 / URL 转为附件 |
| 图片生成资产化 | 复用 AGENT 节点 `capability: IMAGE`（`CapabilityDispatcher` 已有 IMAGE 分支）；产物经 `MediaStore`（`FileSystemMediaStore` / `ObjectMediaStore`）落成资产，回传资产引用写入 scope；`MediaStore` 需接入 `ApplicationBootstrap`（当前未装配，G9） |
| 视频生成 | `capability: VIDEO`（异步任务 + 回调）；接入 `MediaJobScheduler` + 回调校验链路（已实现未接线），新增「提交 → 轮询 / 回调 → 资产」动作 |
| 能力解析扩展 | `CompatibleConfigResolver` / `ConfigResolvers` 按需覆盖新增能力种类（G8） |

## 7. 阶段四：动态编排（描述 → 工作流）与人机交互打通

### 7.1 描述 → 工作流

- 新增 core 接口 `IWorkflowComposer`（`com.clara.core.workflow`）与实现 `LlmWorkflowComposer`：
  - 输入：用户自然语言描述 + **积木清单**（节点类型、可用角色 / 模型别名、可用工具、可用提示词）；
  - 输出：`WorkflowDefinition`（LLM 产出受限 DSL / YAML → 白名单校验 → 构建）。
- 新增 `WorkflowCompositionValidator`：节点类型白名单、引用存在性（tool / prompt / role）、节点数与深度上限（参照 planner `PlanValidator` 思路）。
- `WorkflowDefinitionRegistry` 增加运行期注册 / 覆盖 API（G5）：`registerOrReplace(WorkflowDefinition, List<String> outputs)`，与 `reload()` 的文件索引合并（运行期注册优先或显式冲突报错）。

### 7.2 planner 引擎的去留

`ITaskPlanEngine` / `DefaultTaskPlanEngine` 目前为孤岛（无 `用户输入 → TaskPlan` 生产者）。**建议不启用第二套执行模型**：动态编排统一收敛到工作流引擎（一套调度、一套检查点），planner 仅保留既有测试用途或后续评审决定下线，避免「工作流 + 计划」双轨。

### 7.3 人机交互打通

- `WorkflowRunner` 增加 `resume(instanceId, inputKey, value)`；
- `IWorkflowDefinitionResolver` 以 `WorkflowDefinitionRegistry` 实现并注入（当前 null 注入，G10）；
- 装配 `InMemoryInteractionService` + `InteractionResumeCoordinator`，把 HUMAN 节点的等待 / 恢复接到 CLI；
- 与阶段三的文件落盘统一到「审批 → 落盘」链路，避免旁路写入。

## 8. 实施顺序与验证

| 步骤 | 内容 | 前置 | 验证 |
| --- | --- | --- | --- |
| 1 | 阶段一（G1–G3） | 无 | `mvn -pl clara-application -am test`；多模型 / 多能力单测与端到端 |
| 2 | 阶段二 MVP（纯 YAML 面板） | 步骤 1 | `design_*` 类工作流用例 |
| 3 | 阶段二增强（REVIEW 节点） | 步骤 2 | 多轮讨论用例 |
| 4 | 阶段三（多模态） | 步骤 1 | 图片识别 / 生成 / 视频资产用例 |
| 5 | 阶段四（动态编排 + 交互） | 步骤 1–4 | 描述生成工作流 + HUMAN 恢复用例 |
| 6 | 全量回归 | — | `mvn verify` |

## 9. 风险与边界

1. **别名口径一致性**：别名 = 模型配置文件名去扩展名，须与 `ConfigLocator.resolveModel` 保持一致，否则 `/model` 与节点 `model` 行为分叉。
2. **惰性加载的失败语义**：未注册 / 加载失败的别名一律 fail-fast（配置问题），不做静默降级到默认模型，避免「角色错用模型」这类隐性错误。
3. **生命周期唯一**：`ProviderConfiguration` 的 `close()` 统一由 `ModelRegistry` 负责，杜绝 `ModelSession` 与注册表各自 `close` 造成的二次关闭。
4. **记忆隔离**：窗口记忆按 `ConversationKey(conversationId, provider, model)` 隔离；`conversationId` 中纳入 `role` 可让同实例不同角色互不污染历史。
5. **并行分支写同名键**：PARALLEL 汇合按 `ExecutionScope.merge` 语义（同键覆盖），多角色并行产出须使用**各自独立的 outputKeys**，禁止写同名键。
6. **能力未配置**：节点声明了 `capability` 但所选平台未配置该能力时，须在 `execute` 前 fail-fast（`getCapability` 抛错），错误消息含节点 id、别名与能力名。
