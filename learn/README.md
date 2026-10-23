# Clara 学习手册

> 面向要读懂、维护、扩展 Clara 的开发者。手册按“先全局、再基础能力、再执行链路、最后端到端与扩展”的顺序组织，每一章都可以独立阅读，但建议按章号推进。

![Clara 学习路线图](images/00-learning-map.svg)

*图 0-1 学习路线：从项目定位到一条完整请求，最后回到扩展与质量。*

## 这本手册解决什么问题

读一个多模块 AI 运行时，最容易犯的错是“从第一个类开始逐行读”。Clara 的模块不少，但真正需要先建立的只有三条线：

- **启动线**：`ClaraApplication` 如何找到配置、探活模型、装配各模块。
- **请求线**：一条用户输入如何经过上下文、模型、工具，最后回到终端。
- **扩展线**：新增模型、工具、MCP、工作流或记忆后端时，应该改哪里、不应该改哪里。

本手册围绕这三条线展开，不追求覆盖每个方法，而是帮助你建立“看到类名就知道它在链路哪一段”的能力。

## 适合谁读

- 第一次接触 Clara，需要先把项目跑起来的人。
- 要基于 `ApplicationBootstrap` 做二次开发或嵌入宿主系统的人。
- 要新增模型平台、工具、MCP 服务端、工作流或记忆实现的人。
- 准备做代码评审、故障排查或面试讲解的人。

## 章节导航

| 章节 | 主题 | 配图 | 读完后应该能回答 |
| --- | --- | --- | --- |
| [第 1 章](01-project-overview.md) | 项目定位与整体架构 | `00`、`01` | Clara 是什么、不是什么？模块之间谁依赖谁？ |
| [第 2 章](02-build-run-config.md) | 构建、启动与配置 | `02`、`14` | 配置从哪里读？启动时发生了什么？改文件何时生效？ |
| [第 3 章](03-core-contracts.md) | Core 契约与 DTO | `12` | 模块之间靠什么协作？接口为什么集中在 core？ |
| [第 4 章](04-http-sse-transport.md) | HTTP / SSE 传输 | `13` | 一次网络调用经过哪些层？超时、体积、取消在哪里处理？ |
| [第 5 章](05-model-call-chain.md) | 模型调用链 | `03` | 平台差异如何被配置和注解消化？模型不可用时怎么切换？ |
| [第 6 章](06-memory-and-recall.md) | 记忆与语义召回 | `04` | 窗口、持久化、摘要、语义召回分别负责什么？ |
| [第 7 章](07-mcp.md) | MCP 客户端 | `05` | MCP 如何从配置变成模型可调用的工具？ |
| [第 8 章](08-skill-and-tools.md) | 技能与脚本工具 | `06` | 提示词、工具声明、脚本执行如何串起来？ |
| [第 9 章](09-workflow-engine.md) | 工作流引擎 | `07` | YAML 如何变成可暂停、可恢复的执行计划？ |
| [第 10 章](10-conversation-orchestration.md) | 对话编排 | `08` | Agent Loop 的退出条件、工具往返、干预和故障切换如何工作？ |
| [第 11 章](11-application-cli-observability.md) | 应用装配、CLI 与可观测 | `15` | 终端交互如何接到编排？Span 和指标如何产生？ |
| [第 12 章](12-end-to-end-trace.md) | 端到端请求追踪 | `09` | 一条真实请求从输入到落盘，每一步落在哪个类？ |
| [第 13 章](13-extension-guide.md) | 扩展开发指南 | `10` | 新增模型 / 工具 / MCP / 工作流分别改哪里？ |
| [第 14 章](14-testing-and-quality.md) | 测试与质量门禁 | `11` | 测试怎么分层？覆盖率、Sonar、SpotBugs 怎么跑？ |

## 三种推荐读法

### 1. 第一次接触：按章顺序读

```text
01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 10 → 11 → 12 → 13 → 14
```

第 1～2 章先把项目跑起来；第 3～5 章理解“一次模型调用”；第 6～8 章理解状态与外部工具；第 9～11 章理解执行链路；第 12～14 章做端到端复盘和动手扩展。

### 2. 带任务排查：按链路跳读

- “模型没被调用 / 启动就失败”：看第 2、5 章。
- “显示等待首个响应超时”：看第 2 章的 `modelCheck` 与第 5 章的探活。
- “工具看不到 / MCP 连不上”：看第 7、8 章。
- “工作流停在人工确认 / 恢复不了”：看第 9、10 章。
- “历史丢了 / 换模型后上下文没了”：看第 6 章。
- “响应很慢 / 首字很慢 / 爆 token”：看第 4、6、10 章。
- “想接自己的平台或工具”：看第 13 章。

### 3. 做代码评审：先看约束再看实现

先读第 3 章的契约边界和第 13 章的扩展点，再回到具体实现。判断一个改动是否合理，先问：

- 有没有让能力模块反向依赖 `application`？
- 有没有把平台差异写成 `if (provider == ...)`，而不是配置或注解？
- 有没有把展示逻辑塞进编排层？
- 有没有绕过工具 Schema、权限或人工确认？

## 阅读约定

- **源码路径**：手册中的路径都相对于仓库根，例如 `clara-orchestration/src/main/java/com/clara/orchestration/DefaultConversationOrchestrator.java`。
- **类名**：第一次出现时写全限定类名或包名，之后只写简单类名。
- **图**：所有图都是独立 SVG，放在 `learn/images/`。Markdown 只引用图片，不依赖 Mermaid 插件。
- **命令**：Windows 使用 PowerShell；Linux / macOS 将 `.\` 换成 `./` 即可。
- **版本**：本项目是 Java 8 字节码目标；示例代码不使用 Java 9+ 语法。

## 先跑起来：最小检查

```powershell
mvn -q -pl clara-application -am package -DskipTests
java -Dfile.encoding=UTF-8 -jar clara.jar
```

启动后至少确认三件事：

- 模型可用性检查出现了“可用”项；
- 启动横幅打印了 `sessionId` 和历史目录；
- 输入一句“你好”，能收到流式回复。

如果这一步不通过，先读第 2 章，不要直接进入后面的调用链。

## 读完之后

你应该能够：

- 画出一条从 `ClaraApplication.main` 到模型平台、再回到 `ConsoleView` 的完整路径；
- 解释 `clara-core`、`clara-orchestration`、`clara-application` 三者的边界；
- 判断一个新需求应该落在 model、memory、skill、mcp、workflow、orchestration 还是 application；
- 使用 `/trace`、`/usage`、启动横幅和日志定位常见故障；
- 在不破坏契约的前提下新增一个模型平台、一个脚本工具或一个自定义工作流。

## 配图索引

| 图号 | 文件 | 用途 |
| --- | --- | --- |
| 0-1 | `images/00-learning-map.svg` | 全书学习路线 |
| 1-1 | `images/01-module-dependencies.svg` | 模块依赖与分层 |
| 2-1 | `images/02-startup-flow.svg` | 启动流程 |
| 2-2 | `images/14-config-map.svg` | 配置目录与加载者 |
| 3-1 | `images/12-core-contracts.svg` | Core 契约地图 |
| 4-1 | `images/13-transport-layers.svg` | HTTP / SSE 传输分层 |
| 5-1 | `images/03-request-pipeline.svg` | 一次请求主链路 |
| 6-1 | `images/04-memory-layers.svg` | 记忆分层 |
| 7-1 | `images/05-mcp-lifecycle.svg` | MCP 生命周期 |
| 8-1 | `images/06-skill-loading.svg` | 技能加载与执行 |
| 9-1 | `images/07-workflow-engine.svg` | 工作流引擎 |
| 10-1 | `images/08-orchestrator-loop.svg` | 对话编排 Agent Loop |
| 11-1 | `images/15-console-observability.svg` | 应用入口与可观测 |
| 12-1 | `images/09-end-to-end-trace.svg` | 端到端请求追踪 |
| 13-1 | `images/10-extension-points.svg` | 扩展开发地图 |
| 14-1 | `images/11-testing-quality.svg` | 测试与质量门禁 |