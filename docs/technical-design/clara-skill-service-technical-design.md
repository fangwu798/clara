# clara-skill 服务 — 技术设计文档

> 状态：已审核
> 前置：`clara-skill-service-business-design.md`（已审核）、`conversation-context-call-record-business-design.md`（已审核）、`clara-core-contract-technical-design.md`（已审核）

## 1. 目标

以「技能」（提示词 + 工具方法）为统一单元，实现技能的自动识别、热加载、检索匹配与执行，并实现 core 定义的提示词 / 工具契约；工具方法支持 Python / JavaScript / Shell 三种脚本、以统一协议执行。现有 skill 代码全部删除重写、不做兼容。

命名规范与包结构约束以 `clara-core-contract-technical-design.md` 为准：接口已在 core 定义，本模块只提供 `com.clara.skill.*` 下的实现类；新增 DTO（如技能定义、提示词、工具方法）遵循 Lombok 注解约定。

## 2. 模块依赖

- `clara-core`：实现 `IPromptProvider`、`IToolInvoker`、`IToolExecutor` 契约；
- 技能描述向量化后，通过 core 的语义记忆接口（`ISemanticMemoryStore`）写入与检索（向量由 `IEmbeddingClient` 生成，由宿主装配）。

## 3. 文件结构（要创建的文件）

```
clara-skill/src/main/java/com/clara/skill/
├── prompt/
│   └── DefaultPromptProvider.java         # 实现 IPromptProvider
├── tool/
│   ├── DefaultToolInvoker.java            # 实现 IToolInvoker
│   ├── DefaultToolExecutor.java           # 实现 IToolExecutor（统一执行调度/分发）
│   ├── IScriptExecutor.java               # 脚本执行器内部抽象（可扩展）
│   ├── ScriptExecutorRegistry.java        # 运行类型 → 执行器 注册/查找
│   ├── ScriptExecutionSpec.java           # 统一执行描述（运行类型/脚本/参数/超时/权限）
│   └── script/
│       ├── PythonScriptExecutor.java      # Python 脚本执行
│       ├── JavaScriptScriptExecutor.java  # JavaScript 脚本执行
│       └── ShellScriptExecutor.java       # Shell 脚本执行
├── registry/
│   ├── SkillRegistry.java                 # 目录扫描 + 热加载
│   ├── SkillLoader.java                   # 技能 / 提示词文件解析
│   └── SkillRegistryErrors.java           # 注册领域错误
├── executor/
│   ├── SkillExecutor.java                 # 同步 / 流式 / 恢复执行
│   ├── SkillSchemaValidator.java          # 输入 / 输出 Schema 校验
│   └── SkillPermissionAuthorizer.java     # 权限校验
└── SkillErrors.java                       # 技能领域错误
```

## 4. 技能目录与文件组织

工具方法与提示词分开存放，工具脚本进一步按运行类型分类存放：

| 目录 | 内容 |
| --- | --- |
| `tools/` | 工具方法文件，每个文件一个工具方法；按脚本运行类型分类（`python/`、`javascript/`、`shell/`） |
| `prompt/` | 提示词文件，每个文件一个提示词 |

识别位置（两类来源，均可按分类递归识别）：

1. 内置默认（系统技能）：skill 模块内 `resource/config`，作为系统附带的技能 / 提示词；
2. 运行期扩展（自定义技能）：当前运行目录 `config/skill`，作为用户扩展的技能 / 提示词。

覆盖规则：两类来源存在同名文件时，运行目录 `config/skill` 中的自定义内容覆盖 `resource/config` 中的系统内容（提示词与工具脚本均适用）。

支持递归识别子目录中的合规文件，文件格式需标准化；脚本运行类型以文件内统一描述信息为准，目录分类仅作组织约定。

## 5. 文件格式（Schema）

### 5.1 工具方法文件（`tools/*`）

采用标准化格式（YAML / JSON，最终在实现设计阶段定稿），每个工具方法包含统一的识别描述信息：

- `id`：技能 ID（唯一）；
- `name`：工具方法名（对应模型工具调用名）；
- `type`：脚本运行类型（`ScriptTypeEnum`：`PYTHON` / `JAVASCRIPT` / `SHELL`），决定由哪个执行器运行；
- `description`：语义描述（用于向量化匹配）；
- `parameters`：参数 JSON Schema；
- `script`：脚本引用（文件路径或内联脚本，运行环境由 `type` 声明）；
- `metadata`：领域、标签等元数据。

### 5.2 提示词文件（`prompt/*`）

每个提示词包含：

- `id`：提示词 ID；
- `description`：语义描述（用于向量化匹配）；
- `segments`：多段、多角色提示词文本。

## 6. 实现类清单（每个类含作用 / 使用范围 / 为什么需要）

### 6.1 提示词

**`DefaultPromptProvider`（`com.clara.skill.prompt`，实现 `IPromptProvider`）**
- 作用：按标识取回提示词定义（含总结提示词）。
- 使用范围：上下文超限压缩取总结提示词；由本模块实现。
- 为什么需要：core 定义契约，这里落地提示词加载与同名覆盖。

### 6.2 工具

**`DefaultToolInvoker`（`com.clara.skill.tool`，实现 `IToolInvoker`）**
- 作用：框架调用技能声明的工具方法（工具往返统一入口）。
- 使用范围：对话编排工具往返循环。
- 为什么需要：屏蔽工具方法定位与执行细节，统一工具调用视图。

**`DefaultToolExecutor`（`com.clara.skill.tool`，实现 `IToolExecutor`）**
- 作用：统一执行工具方法对应的脚本（Python / JavaScript / Shell）并返回结果。
- 使用范围：工具方法实际执行；按运行类型经注册表分派到具体脚本执行器。
- 为什么需要：统一执行调度与安全隔离（独立进程、权限、超时），脚本类型可扩展、不写死。

**`IScriptExecutor`（`com.clara.skill.tool`）**
- 作用：脚本执行器内部抽象，定义「运行环境 → 执行脚本 → 返回结果」的统一约定。
- 使用范围：各脚本运行类型的执行器实现此约定。
- 为什么需要：屏蔽不同脚本运行环境的差异，新增脚本类型只需新增实现。

**`ScriptExecutorRegistry`（`com.clara.skill.tool`）**
- 作用：维护「运行类型 → 执行器」的注册与查找。
- 使用范围：`DefaultToolExecutor` 按工具方法 `type` 分发时。
- 为什么需要：执行器可注册扩展，避免在调度逻辑中写死脚本类型。

**`ScriptExecutionSpec`（`com.clara.skill.tool`）**
- 作用：描述一次脚本执行的统一上下文（运行类型、脚本、参数、超时、权限约束）。
- 使用范围：`DefaultToolExecutor` → 具体执行器的入参。
- 为什么需要：统一执行描述，作为调度与各执行器之间的公共数据契约。

**`PythonScriptExecutor` / `JavaScriptScriptExecutor` / `ShellScriptExecutor`（`com.clara.skill.tool.script`）**
- 作用：分别执行 Python / JavaScript / Shell 脚本。
- 使用范围：`ScriptExecutorRegistry` 注册的默认三种实现。
- 为什么需要：承载各运行环境差异（解释器路径、命令行、环境变量），调用协议保持一致。

### 6.3 注册与加载

**`SkillRegistry`（`com.clara.skill.registry`）**
- 作用：按分类扫描 `tools/`、`prompt/` 目录（内置 `resource/config` 与运行期 `config/skill` 两处来源），识别技能与提示词，支持热加载与同名覆盖。
- 使用范围：服务启动全量扫描、运行期增量识别。
- 为什么需要：技能的「仓库」，统一管理技能标识与检索。

**`SkillLoader`（`com.clara.skill.registry`）**
- 作用：解析技能 / 提示词文件为结构化定义。
- 使用范围：`SkillRegistry` 扫描时。
- 为什么需要：隔离文件格式与解析逻辑，便于格式演进。

**`SkillRegistryErrors`（`com.clara.skill.registry`）**
- 作用：集中创建注册领域错误。
- 使用范围：注册与加载内部。
- 为什么需要：统一错误码与分类。

### 6.4 执行

**`SkillExecutor`（`com.clara.skill.executor`）**
- 作用：同步 / 流式 / 恢复三种方式编排技能执行（提示词装配 + 工具往返 + 结果校验 + 生命周期）。
- 使用范围：技能被调用时。
- 为什么需要：统一技能级执行方式与生命周期（取消、权限、超时、结果校验）；原子脚本执行委托 `IToolExecutor`，不重复实现脚本运行。

**`SkillSchemaValidator`（`com.clara.skill.executor`）**
- 作用：执行前校验输入 Schema、执行后校验输出 Schema 与技能标识一致性。
- 使用范围：技能执行前后。
- 为什么需要：保证输入输出合法。

**`SkillPermissionAuthorizer`（`com.clara.skill.executor`）**
- 作用：校验技能执行权限。
- 使用范围：技能执行前。
- 为什么需要：权限检查与执行逻辑解耦。

**`SkillErrors`（`com.clara.skill`）**
- 作用：集中创建技能领域错误。
- 使用范围：本模块内部。
- 为什么需要：统一错误码与分类。

## 7. 工具方法执行（Python / JavaScript / Shell）

工具方法支持 Python、JavaScript、Shell 三种脚本，调用协议一致——仅脚本编写环境与运行环境不同。`DefaultToolInvoker` 是工具往返的统一入口，按工具名 / 技能 ID 定位工具方法后，委托 `DefaultToolExecutor` 统一调度执行。

**统一执行链路**：

1. `IToolInvoker` 从 `SkillRegistry` 取回工具方法定义（含 `type`、`script`、`parameters`、`metadata`）；
2. 执行前校验：`SkillSchemaValidator` 校验入参是否符合 `parameters` Schema；`SkillPermissionAuthorizer` 校验执行权限；再检查取消信号与截止时间；
3. `DefaultToolExecutor` 组装 `ScriptExecutionSpec`，经 `ScriptExecutorRegistry` 按 `type` 分派到对应脚本执行器；
4. 执行后校验：`SkillSchemaValidator` 校验输出 Schema 与技能标识一致性。

**统一进程执行（各脚本执行器）**：

- 用 `ProcessBuilder` 独立进程执行；运行环境由各执行器解析（Python 取 `PYTHON_PATH`、JavaScript 取 `NODE_PATH`、Shell 取系统 shell）；
- 调用协议统一：入参通过 stdin 以 JSON 下发给脚本，结果从 stdout 以 JSON 读取，stderr 单独捕获用于错误定位；
- 每次执行使用独立工作目录，脚本引用来自工具方法定义的 `script` 字段（文件路径或内联），避免工具间文件污染；
- 执行结果统一返回为结构化对象，进程细节不外泄。

**约束与中断**：

- 硬超时：每个工具方法执行带截止时间，超时即销毁进程（含子进程）并判定为超时失败；
- 取消：执行前与执行中均检查取消信号，取消即中断；
- 权限：`SkillPermissionAuthorizer` 决定是否允许执行（能力白名单、可访问目录、网络等约束）；
- 失败归类：非零退出码、stdout 非合法 JSON、输出 Schema 不符、超时、权限拒绝、未知运行类型等统一映射到 `SkillErrors` 领域错误。

**可扩展性**：新增脚本类型需新增 `ScriptTypeEnum` 枚举值、实现 `IScriptExecutor` 并注册到 `ScriptExecutorRegistry`，无需改动统一调度逻辑。

## 8. 技能执行方式

`SkillExecutor` 统一承载技能执行的生命周期（取消、权限、超时、结果校验），对外提供同步 / 流式 / 恢复三种执行方式，并以句柄状态机统一描述执行状态。它是技能级「编排」层——通过 `IToolInvoker` / `IToolExecutor` 调用工具方法脚本，与第 7 节的原子脚本执行是「编排 vs 原子执行」关系，不重复实现脚本运行。

**执行状态机**：`RUNNING`（执行中）→ `COMPLETED`（成功）/ `CANCELLED`（已取消）/ `FAILED`（失败，含超时）。终态后重复取消无副作用，句柄始终可查询完成状态。

**同步执行**：提交请求后阻塞等待，直到执行进入终态后返回结果；内部复用异步执行并阻塞取结果。

**流式执行**：提交后立即返回句柄，通过事件回调接收进度（阶段变更、部分输出、心跳等），可随时经句柄取消；客户端持句柄即可观察与中断，无需阻塞等待。

**恢复执行**：对已中断（取消 / 失败 / 超时）的执行，基于保存的中间状态续跑；恢复入口校验执行身份与状态，仅允许从可恢复的中断态续跑。

**通用约束**：三种方式均强校验取消信号、权限与截止时间，超时未完成即中断；执行前校验输入 Schema，执行后校验输出 Schema 与技能标识一致性。

## 9. 基于检索的技能匹配

技能匹配通过「描述向量化 → 用户输入检索 → 相关性筛选」实现按需动态装配提示词与工具方法，底层复用 core 的语义记忆契约（`ISemanticMemoryStore` + `IEmbeddingClient`），不直接依赖向量库。

**描述入库（注册 / 热加载时）**：

- 提示词与工具方法的 `description` 统一经 `IEmbeddingClient` 向量化；
- 以 `MemoryEntry` 写入 `ISemanticMemoryStore`，来源类型分别为 `PROMPT`（提示词）与 `TOOL_DESCRIPTION`（工具方法），元数据携带技能 ID / 领域 / 标签；
- 技能描述条目为全局作用域（不关联会话），检索按来源类型过滤；
- 热加载时同步刷新：新增 / 变更 → 重新向量化并 `upsert`；删除 → 以 `{来源类型}:{技能ID}` 作为条目标识经 `deleteById` 移除对应条目，保证向量库与 `SkillRegistry` 一致；
- 嵌入模型需全程一致（入库与检索同模型、同维度），更换嵌入模型需重建索引。

**调用时检索**：

- 每次调用用当前用户输入经 `IEmbeddingClient` 向量化，构造 `SemanticMemoryQuery`（来源类型限定 `PROMPT` / `TOOL_DESCRIPTION`、topK、相似度阈值）调用 `ISemanticMemoryStore.search`；
- 召回相似度默认 70%、topK 默认 3，均可在 control 节点配置。

**结果装配**：

- 按相似度排序并过滤低于阈值的命中，同技能去重；命中的提示词定义经 `IPromptProvider` 取回，命中的工具方法定义组装为模型请求的工具声明（`tools`）；
- 提示词作为 system / instructions 的一部分注入，工具声明随请求下发，实现按需动态装配、不每次全量加载。

## 10. 非目标

- 不涉及观测日志系统（脱敏 / 截断 / SLF4J 等）；
- 脚本运行时环境（Python / JavaScript / Shell）的具体选型与调度线程模型在实现设计阶段细化。
