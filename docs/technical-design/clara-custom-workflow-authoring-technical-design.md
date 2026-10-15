# clara 自定义工作流生成与热加载 — 技术设计文档

> 状态：已实现（2026-10-15）
> 前置：`clara-workflow-service-technical-design.md`、`clara-skill-service-technical-design.md`

## 1. 目标

让用户不改代码、不手写 YAML，就能把「自己常做的一套流程」变成 Clara 的一等工作流：

- 用自然语言提出诉求，模型起草 / 自检 / 发布一份完整的工作流定义；
- 生成物统一落在 `config/workflow/custom/`，与随应用发布的内置流程分开管理；
- 会话内立即生效，**无需重启**；
- 模型能看到「当前有哪些流程」，用户在聊天里点名要用某个流程时，模型通过 `run_workflow` 按标识执行。

## 2. 能力分层

| 层 | 产物 | 职责 |
| --- | --- | --- |
| 生成 | `config/workflow/author_custom_workflow.yml` | 读样例 → 查已有 → 起草 → 自检 → 发布，全程在对话内完成 |
| 编写规范 | `config/skill/prompt/workflow_author.json`、`workflow_author_reviewer.json` | 把节点类型 / 变量占位 / 字段约束作为提示词注入模型 |
| 校验与落盘 | 内置工具 `publish_workflow` | 用运行期同一套加载器校验，通过后写入 custom 目录 |
| 运行期生效 | `WorkflowDefinitionRegistry.refresh()` | 按文件签名自动重载，解析失败保留旧索引 |
| 模型可见性 | `WorkflowCatalog` + `IToolDescriptionDecorator` | 把可用流程清单拼进 `run_workflow` 的工具描述 |
| 人可见性 | `ConsoleView.printHints` | 启动时提示自定义流程清单 |

## 3. 文件清单

### 3.1 新增

```
clara-application/src/main/java/com/clara/workflow/
├── WorkflowCatalog.java                    # 工作流清单：扫描、描述、拼模型可见清单、装饰工具描述
└── PublishWorkflowTool.java                # 内置工具 publish_workflow：校验并发布自定义工作流
clara-core/src/main/java/com/clara/core/skill/
└── IToolDescriptionDecorator.java          # 工具描述装饰器契约（core 层不感知工作流）
config/workflow/
├── author_custom_workflow.yml              # 工作流生成器本身就是一个工作流
└── custom/README.md                        # 自定义目录使用说明（放置 / 生效 / 命名 / 排错）
config/skill/prompt/
├── workflow_author.json                    # 起草提示词：编写规范 + 骨架示例
└── workflow_author_reviewer.json           # 自检提示词：按同一套规范逐条核对草稿
config/skill/tools/builtin/
└── publish_workflow.json                   # publish_workflow 的工具声明
```

### 3.2 改动

| 文件 | 改动 |
| --- | --- |
| `clara-workflow/.../definition/WorkflowDefinitionRegistry.java` | 新增 `refresh()` / `lastError()` / `parseDefinition()` / `directory()`，按文件签名增量重载，失败保留旧索引 |
| `clara-workflow/.../runtime/WorkflowRunner.java` | `run()` 前先 `refresh()`；标识不存在时把加载失败原因拼进异常消息 |
| `clara-core/.../skill/IToolDescriptionDecorator.java` | 新增描述装饰器契约，避免 core 反向依赖工作流 |
| `clara-skill/.../registry/RegistryToolCatalog.java` | 新增 `(registry, decorator)` 构造，装配后按工具名装饰描述 |
| `clara-application/.../workflow/AgentWorkflowAction.java`、`AgentActionFactory.java` | AGENT 节点支持 `promptText` 内联提示词（`prompt` 与 `promptText` 二选一） |
| `clara-application/.../tool/ToolInvocationPurpose.java` | 调用记录里的工作流描述改用 `WorkflowCatalog.describe`，自定义流程也能显示描述 |
| `clara-application/.../ApplicationBootstrap.java` | 注册 `publish_workflow`、接入工具描述装饰器 |
| `clara-application/.../adapter/console/ConsoleView.java`、`ClaraApplication.java` | 启动提示补充自定义工作流清单 |
| `config/skill/tools/builtin/run_workflow.json` | 描述中文化，说明可用清单会动态追加在末尾 |
| `config/skill/scripts/file-ops/file_ops.py` | `read_many` 支持通配模式与 `max_bytes` / `max_files` 上限 |
| `config/workflow/design_business_solution.yml`、`plan_development.yml` | 技术规范检索改为 `config/standards/*`（整目录一次性读取） |

## 4. 关键实现细节

### 4.1 生成器工作流（`author_custom_workflow`）

根节点是 SEQUENCE，五个子节点：

| 节点 | 类型 | 作用 |
| --- | --- | --- |
| `read_samples` | TOOL `file_ops.read_many` | 读 `analyze_repo_docs` / `design_business_solution` / `image_generation` 三份内置样例 |
| `list_custom` | TOOL `file_ops.scan` | 列 `config/workflow/custom` 下已有流程，避免标识重复并支持改造 |
| `draft` | AGENT `workflow_author` | 按诉求把样例写法转写成一份完整、可直接加载的 YAML |
| `review` | AGENT `workflow_author_reviewer` | 按同一套规范逐条自检草稿，产出最终版本 |
| `publish` | TOOL `publish_workflow` | 校验 + 落盘，回执给出标识、路径与调用方式 |

两个设计取舍：

- **样例即规范**：起草前先读内置样例，让生成的写法与仓库现有流程保持一致，而不是靠模型凭空发明节点字段。
- **只回传回执**：`outputs: [published]`，避免把样例与草稿整体回灌给对话模型，占用上下文。

### 4.2 单一校验通道（`publish_workflow`）

`PublishWorkflowTool` 的流程：把入参 `yaml` 写进系统临时文件 → **用运行期同一个 `WorkflowDefinitionLoader` 解析校验** → 校验 `workflowId` 合法（`[A-Za-z0-9_-]{1,64}`）→ 拒绝与内置流程重名 → 写入 `config/workflow/custom/<workflowId>.yml` → 调 `registry.refresh()` 立即生效。

要点：

- 校验与运行共用一套加载器，**不会出现「发布通过但加载失败」**；
- 定义非法时把加载器报错原样回传（含节点 id），并把临时文件路径替换成「待发布的自定义工作流」，避免模型看到无意义的临时路径；
- 支持 `dryRun=true` 只校验不落盘，供交付前自检；
- 回执含 `replaced`，改造已有流程时能看出是覆盖还是新增。

### 4.3 运行期热刷新（`WorkflowDefinitionRegistry.refresh()`）

按文件签名（`mtime:size`）与上次加载结果比对，签名未变则直接返回；有变化才重新解析。

两条关键语义：

- **失败保留旧索引**：某个文件写坏时，其余工作流照常可用；失败原因记入 `lastError`，**且不更新该文件的签名**，所以修好文件后下次调用会自动重试；
- **错误可见**：`WorkflowRunner` 在标识不存在时把 `lastError` 拼进异常消息（含文件名，并说明「修正后重试自动生效」），用户不用翻日志就知道哪份定义坏了。

### 4.4 模型可见清单（工具描述装饰）

`WorkflowCatalog` 每次现扫现取 `config/workflow`（不做进程内缓存），因此刚发布的自定义流程立刻出现在清单里。定位口径：优先 `CLARA_WORK_DIR`，否则自当前目录向上回溯 5 层，兼容从模块子目录启动。

清单文本形如 `内置工作流：xxx（描述；必填入参：a, b）、自定义工作流（config/workflow/custom 下，写入即可用）：...`，由 `decorateToolDescription` 只追加到 `run_workflow` 的描述末尾——装饰器契约放在 core，工作流知识留在 application。

`file_ops` 用 `read_many` 的 `paths` 一次读整目录（如 `config/standards/*`），把「编写规范」这类分散文件作为一次工具调用的上下文喂给模型，而不是让模型逐个文件读。

### 4.5 AGENT 节点内联提示词（`promptText`）

AGENT 节点的提示词改为二选一：`prompt`（既有提示词标识）或 `promptText`（内联正文）。这样一份自定义 YAML 可以自成一体，不必再额外维护提示词文件；未声明 `promptText` 时行为与既往完全一致。

### 4.6 `file_ops` 通配读取（`read_many`）

`paths` 元素支持 `* ? []` 与 `**` 递归；新增 `max_files`（默认 200）与已有的 `max_bytes`（单文件截断，默认 20000）共同防止模式写得太宽读爆上下文，输出带 `truncated` 标记。

## 5. 使用方式

- **对话式（推荐）**：直接说「帮我做一个……的工作流」，即触发 `author_custom_workflow`，发布完成后当场可用。
- **直接发布**：让模型调 `publish_workflow`（`dryRun=true` 先自检），适合手写定义时的交付前校验。
- **手动落盘**：自己写 `config/workflow/custom/<标识>.yml` 放进去即可，无需重启。
- **运行**：聊天里点名流程，模型按标识调 `run_workflow`；也可显式指定标识。

## 6. 排错对照

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `run_workflow` 报「未找到工作流标识」且带文件名 | 该定义解析失败 | 按报错里的节点 id 修正，保存后自动重试，无需重启 |
| 发布被拒绝、提示与内置流程重名 | 标识与内置流程冲突 | 换标识，或改造时保留原标识（仅限 custom 目录内覆盖） |
| 流程跑起来但拿不到预期入参 | `inputSchema` 必填项与实际调用不一致 | 修正 `inputSchema`，或调用时补齐入参 |
| 生成的 YAML 字段不认识 | 模型未按样例写法生成 | 让模型重跑生成器；或对照 `workflow_author.json` 自查后重新发布 |

## 7. 测试与验证

单测：

- `clara-workflow/.../definition/WorkflowDefinitionRegistryTest.java`：签名命中不重载、文件变化重载、解析失败保留旧索引且修好后自动恢复、`lastError` 内容；
- `clara-workflow/.../runtime/WorkflowRunnerTest.java`：运行前刷新、标识不存在时异常带失败原因；
- `clara-application/.../workflow/PublishWorkflowToolTest.java`：非法定义拒绝落盘、合法定义落盘并生效、`dryRun` 不落盘、非法标识拒绝、与内置重名拒绝、覆盖已有自定义流程。

端到端冒烟（真实运行 `clara.jar`）：启动即加载到 `author_custom_workflow`；模型侧清单含「必填入参：requirement」；发布回执为「已发布并立即生效」；发布后清单立即可见；`run_workflow <自定义标识>` 返回 `SUCCEEDED` 且 outputs 为真实工具结果。

## 8. 边界与未做

- 自定义流程与内置流程同目录树加载，**标识不得与内置重名**（发布时即拒绝），避免覆盖随应用发布的流程；
- 生成质量取决于模型：流程复杂时建议人工复核生成的 YAML，或把它当作草稿再改；
- 热刷新按 `mtime:size` 签名，若在极短时间内以完全相同的大小改写文件，可能漏检该次变更；
- `file_ops` 读取超大文件（如篇幅很长的规范 HTML）仍受 `max_bytes` 截断，属于工具既有边界。