# clara-mcp 服务 — 技术设计文档

> 状态：已审核
> 前置：`clara-mcp-service-business-design.md`（已审核）、`clara-core-contract-technical-design.md`（已审核）、`clara-orchestration-service-technical-design.md`（已审核）

## 1. 目标

实现基于 MCP（Model Context Protocol）协议的外部服务联动：服务端注册与发现、能力目录（工具 / 资源 / 资源模板 / 提示词）、统一调用入口（工具调用 / 资源读取 / 提示词获取）与传输适配（stdio / streamable http），供编排层工具往返中的「外部工具」使用。

本模块**整体按新业务重写、不做兼容**：`registry` / `client` / `transport` 三层均按本设计重写，现有代码仅作参考。命名规范与包结构以 `clara-core-contract-technical-design.md` 为准：对外接口统一放在 `com.clara.mcp.api` 包并加 `I` 前缀，实现类留在各自职责包，不使用类内嵌套接口。

## 2. 模块依赖

- `clara-core`：跨层错误框架（`ClaraException` / `ClaraError` / `ErrorCodes` / `ErrorCategory`），
  以及通用传输契约（`com.clara.core.transport`：`IHttpTransport` / `IHttpCall` /
  `RawHttpRequest` / `RawHttpResponse` / `HttpTransportPolicy` / `ISseEventParser`）；
- `clara-transport`：通用 HTTP / SSE 传输实现（`JdkHttpTransport`、`DefaultSseEventParser`、
  `BoundedStreams`、`DaemonThreads`）；
- `clara-model`：会话上下文（`ConvContext`）与调用上下文（`CallContext`）；
- 依赖方向：clara-mcp 不依赖 memory / skill / workflow / orchestration；编排层依赖本模块。

## 3. 文件结构（要创建的文件）

```
clara-mcp/src/main/java/com/clara/mcp/
├── api/                                   # 对外契约：只放接口，统一 I 前缀
│   ├── IMcpClient.java                    # MCP 客户端协议接口
│   ├── IMcpClientFactory.java             # 客户端工厂接口
│   ├── IMcpGateway.java                   # 统一调用入口接口
│   ├── IMcpServerRegistry.java            # 服务端注册表接口
│   ├── IMcpCapabilityCatalog.java         # 能力目录接口
│   ├── IMcpDiscoveryService.java          # 发现服务接口
│   ├── IMcpTransport.java                 # 传输接口
│   ├── IMcpTransportListener.java         # 传输监听接口
│   ├── IMcpJsonCodec.java                 # JSON 编解码接口
│   ├── IProcessFactory.java               # 进程工厂接口
│   └── IMcpHeadersProvider.java           # http 鉴权头接口
├── McpErrors.java                         # 模块统一错误工厂
├── registry/
│   ├── InMemoryMcpServerRegistry.java     # 内存注册表
│   ├── InMemoryMcpCapabilityCatalog.java  # 内存能力目录
│   ├── DefaultMcpDiscoveryService.java    # 发现服务实现
│   ├── McpServerRegistration.java         # 服务端注册项
│   ├── McpServerSnapshot.java             # 能力快照
│   ├── McpRegistrationStatus.java         # 注册状态枚举
│   ├── McpTransportProfile.java           # 传输配置枚举
│   ├── McpHealthStatus.java               # 健康状态枚举
│   ├── config/
│   │   ├── McpServerConfig.java               # 服务端配置值对象（stdio/http 字段）
│   │   ├── McpServerConfigLoader.java         # 单文件 json 配置加载（含 env 插值）
│   │   ├── McpServerConfigDirectoryLoader.java# 本地目录 / classpath 配置识别与合并
│   │   └── McpServerRegistrar.java            # 配置 → 注册项 → 注册表的装配入口
│   └── capability/
│       ├── McpToolDescriptor.java             # 工具描述
│       ├── McpResourceDescriptor.java         # 资源描述
│       ├── McpResourceTemplateDescriptor.java # 资源模板描述
│       ├── McpPromptDescriptor.java           # 提示词描述
│       ├── McpPromptArgument.java             # 提示词参数
│       └── SchemaHasher.java                  # Schema 哈希
├── client/
│   ├── DefaultMcpClient.java              # 客户端协议实现
│   ├── DefaultMcpClientFactory.java       # 客户端工厂实现
│   ├── McpInitialization.java             # 初始化结果
│   ├── McpServerIdentity.java             # 服务端身份
│   ├── McpServerCapabilities.java         # 服务端能力
│   ├── McpPage.java                       # 分页
│   ├── gateway/
│   │   └── DefaultMcpGateway.java             # 统一调用入口实现
│   └── call/
│       ├── McpToolCall.java                   # 工具调用入参
│       ├── McpToolResult.java                 # 工具调用结果
│       ├── McpResourceRequest.java            # 资源读取入参
│       ├── McpResourceContent.java            # 资源内容
│       ├── McpPromptRequest.java              # 提示词获取入参
│       └── McpPromptResult.java               # 提示词结果
└── transport/
    ├── JacksonMcpJsonCodec.java           # Jackson 实现
    ├── McpDecodedMessage.java             # 解码消息
    ├── jsonrpc/
    │   ├── JsonRpcRequest.java                # JSON-RPC 请求
    │   ├── JsonRpcResponse.java               # JSON-RPC 响应
    │   ├── JsonRpcError.java                  # JSON-RPC 错误
    │   └── JsonRpcNotification.java           # JSON-RPC 通知
    ├── stdio/
    │   └── StdioMcpTransport.java             # stdio 传输
    └── http/
        └── StreamableHttpMcpTransport.java    # streamable http 协议适配（HTTP/SSE 委托 clara-transport）
```

## 4. 实现类清单（作用 / 使用范围 / 为什么需要 / 业务细节 / 技术细节）

### 4.1 注册与目录（`com.clara.mcp.registry`）

**`IMcpServerRegistry`（`com.clara.mcp.api`）/ `InMemoryMcpServerRegistry`（`com.clara.mcp.registry`）**
- 作用：登记并查找 MCP 服务端。
- 使用范围：发现与调用前定位服务端。
- 为什么需要：统一管理服务端接入信息与注册状态。
- 业务细节：`register` 登记服务端；`listEnabled` 列出某租户下可用服务端；`get` 按租户 + 服务端标识取回；`updateStatus` 更新注册状态（可用 / 不兼容 / 需复核 / 停用）。
- 技术细节：接口方法 `register(McpServerRegistration)` / `updateStatus(tenantId, serverKey, McpRegistrationStatus)` / `listEnabled(tenantId)` / `get(tenantId, serverKey)`；内存实现用 `ConcurrentHashMap`，键为「租户 + 服务端标识」。

**`IMcpDiscoveryService`（`com.clara.mcp.api`）/ `DefaultMcpDiscoveryService`（`com.clara.mcp.registry`）**
- 作用：初始化服务端并分页发现其能力，发布能力快照。
- 使用范围：服务端注册后的发现流程。
- 为什么需要：外部能力必须先发现入目录才能被解析与调用。
- 业务细节：`discover` 初始化连接、校验协议版本与服务端身份、按能力分页拉取工具 / 资源 / 资源模板 / 提示词并形成快照；`refresh` 发现成功后将快照发布到目录并把注册状态更新为可用，协议不兼容标记不兼容、身份变化标记需复核。
- 技术细节：依赖 `IMcpServerRegistry` + `IMcpCapabilityCatalog` + `IMcpClientFactory`；校验协议版本在支持列表、身份与已审批身份一致；分页受 `maxPages` 约束、游标去重；`finally` 关闭客户端；快照含协议版本、身份、能力、健康状态。

**`IMcpCapabilityCatalog`（`com.clara.mcp.api`）/ `InMemoryMcpCapabilityCatalog`（`com.clara.mcp.registry`）**
- 作用：保存各服务端能力快照并按标识解析能力。
- 使用范围：调用前解析工具 / 资源 / 提示词。
- 为什么需要：把「发现」与「调用」解耦，调用方按目录解析能力。
- 业务细节：`publish` 保存快照；`latest` 取最新快照；`get` 按快照标识取回；`resolveTool` / `resolveResource` / `resolvePrompt` 按名称与期望 Schema 哈希解析，哈希不一致则拒绝。
- 技术细节：接口方法如上；内存实现按「租户 + 服务端标识（+ 快照标识）」索引；解析时比对 `SchemaHasher` 计算的哈希与期望值，不一致抛 `MCP_SCHEMA_MISMATCH`。

**`McpServerRegistration` / `McpServerSnapshot` / `McpRegistrationStatus` / `McpTransportProfile` / `McpHealthStatus`（`com.clara.mcp.registry`）**
- 作用：服务端注册项、能力快照与状态 / 传输 / 健康枚举。
- 使用范围：注册、发现与目录。
- 为什么需要：注册与快照的数据载体与状态取值。
- 业务细节：注册项承载租户、服务端标识、传输方式（含地址 / 鉴权）与已审批身份；快照承载快照标识、租户、服务端标识、发现时间、协议版本、身份、能力、各类能力列表与健康状态。
- 技术细节：不可变值对象 + 构造期校验；`McpRegistrationStatus`（可用 / 不兼容 / 需复核 / 停用）、`McpTransportProfile`（stdio / streamable http）、`McpHealthStatus`（健康 / 降级 / 不健康）。

**`McpErrors`（`com.clara.mcp`）**
- 作用：模块内唯一的领域错误工厂，集中创建客户端、注册 / 发现与传输错误。
- 使用范围：本模块内部。
- 为什么需要：统一错误码与分类（协议不兼容 / 身份变化 / 分页超限 / 游标重复 / 发现失败 / 非法消息 / 服务端或能力未找到 / Schema 不一致），避免各层各自维护一套工厂。
- 技术细节：`error(String, ErrorCategory)` 默认不可重试，`error(String, ErrorCategory, boolean retryable)` 显式声明可重试性；返回 `ClaraException(ClaraError)`，错误码取自 `ErrorCodes` 的 `MCP_*` 系列。

### 4.2 能力描述（`com.clara.mcp.registry.capability`）

**`McpToolDescriptor` / `McpResourceDescriptor` / `McpResourceTemplateDescriptor` / `McpPromptDescriptor` / `McpPromptArgument` / `SchemaHasher`（`com.clara.mcp.registry.capability`）**
- 作用：工具 / 资源 / 资源模板 / 提示词的能力描述与 Schema 哈希计算。
- 使用范围：快照内容与目录解析结果。
- 为什么需要：统一能力描述结构，用 Schema 哈希保证调用与目录一致。
- 业务细节：工具含名称、描述、入参 Schema；资源含 URI 与 mime 类型；资源模板含 URI 模板；提示词含名称、描述与参数（参数含名称、描述、是否必填）。
- 技术细节：不可变值对象 + 构造期校验；`McpToolDescriptor` 构造时用 `SchemaHasher` 计算 `schemaHash`（对入参 Schema 做规范化哈希）。

### 4.3 配置（`com.clara.mcp.registry.config`）

**`McpServerConfig` / `McpServerConfigLoader` / `McpServerConfigDirectoryLoader` / `McpServerRegistrar`（`com.clara.mcp.registry.config`）**
- 作用：把外部 `mcp.json` 配置（Claude Desktop / VS Code 风格）加载为服务端配置、识别本地目录中的多个配置、并转换为注册项登记入注册表。
- 使用范围：宿主（如 clara-application 装配层）启动时读取配置并注册服务端。
- 为什么需要：注册目前只能由调用方逐条构造 `McpServerRegistration`；引入标准 `mcp.json` 加载与目录识别，让宿主声明式接入外部服务端，避免手工拼装传输参数。
- 业务细节：`McpServerConfigLoader` 读取顶层 `mcpServers` 映射，解析 stdio 的 `command` / `args` / `env` 与 http 的 `url` / `headers`，并推断传输方式；`McpServerConfigDirectoryLoader` 扫描运行目录 `config/mcp`（递归 `*.json`，可选叠加 classpath 内置 `config/mcp`）并按覆盖规则合并；`McpServerRegistrar.registerAll(tenantId, configs)` 把配置转换为注册项并登记。
- 技术细节：`McpServerConfig` 为不可变值对象（`serverKey` / `transportProfile` / `command`(`List<String>`) / `env` / `url` / `headers`），`of(...)` 从字段推断传输方式，构造期校验 stdio/http 字段互斥；`env` / `headers` 值支持 `${VAR}` 从环境变量插值（未定义报错并指明变量名）；同名 `serverKey` 覆盖规则为「运行目录覆盖 classpath 内置、同层后加载覆盖先加载」；注册项 `address` 由 stdio 命令行拼接或 http `url` 生成。

### 4.4 客户端协议（`com.clara.mcp.client`）

**`IMcpClient`（`com.clara.mcp.api`）/ `DefaultMcpClient`（`com.clara.mcp.client`）**
- 作用：MCP 客户端协议（初始化、列表分页、工具调用、资源读取、提示词获取）。
- 使用范围：发现与调用时经工厂创建。
- 为什么需要：把 MCP 协议交互集中在客户端，屏蔽传输细节。
- 业务细节：客户端完成协议初始化并返回能力与身份；提供工具 / 资源 / 资源模板 / 提示词的分页列表；提供工具调用、资源读取、提示词获取；关闭释放传输。
- 技术细节：`DefaultMcpClient(IMcpTransport, long requestTimeoutMillis)`；首次请求惰性 `transport.start(listener)`；请求带唯一 id，`sendRequest(...).get(timeout)`；错误响应 / 非法结构 / 传输异常分别映射为领域错误；`close()` 幂等并释放传输。

**`IMcpClientFactory`（`com.clara.mcp.api`）/ `DefaultMcpClientFactory`（`com.clara.mcp.client`）**
- 作用：按服务端注册项创建客户端（含对应传输）。
- 使用范围：发现与调用。
- 为什么需要：把「传输选型」与「客户端协议」隔离，便于新增传输。
- 业务细节：`create(registration)` 按传输方式装配传输并包装为客户端。
- 技术细节：`stdio` → `StdioMcpTransport`（经 `IProcessFactory` 启动子进程）；`streamable http` → `StreamableHttpMcpTransport`（+ `IMcpHeadersProvider` 鉴权）；未知 / 不支持的传输方式抛 `CAPABILITY_NOT_SUPPORTED`。

**`McpInitialization` / `McpServerIdentity` / `McpServerCapabilities` / `McpPage`（`com.clara.mcp.client`）**
- 作用：初始化结果、服务端身份、服务端能力与分页结果。
- 使用范围：发现流程。
- 为什么需要：承载协议版本、身份、能力与分页数据。
- 业务细节：能力标识服务端是否支持工具 / 资源 / 提示词（各含是否变更）；分页承载当前页条目与下一页游标。
- 技术细节：不可变值对象；`McpPage<T>` 泛型，含 `items`(`List<T>`) 与 `nextCursor`。

### 4.5 调用入口与入参出参（`com.clara.mcp.client.gateway` / `com.clara.mcp.client.call`）

**`IMcpGateway`（`com.clara.mcp.api`）/ `DefaultMcpGateway`（`com.clara.mcp.client.gateway`）**
- 作用：对外统一调用入口（工具调用 / 资源读取 / 提示词获取）。
- 使用范围：编排层外部工具调用。
- 为什么需要：屏蔽客户端与协议细节，向编排层提供稳定入口。
- 业务细节：`callTool` 按服务端标识 + 工具名与参数调用外部工具并返回结果；`readResource` 按资源 URI 读取内容；`getPrompt` 按提示词名与参数获取提示词。
- 技术细节：依赖 `IMcpServerRegistry`（定位服务端、校验注册状态）、`IMcpCapabilityCatalog`（解析能力、校验 Schema 哈希）、`IMcpClientFactory`（创建客户端）；调用前核对取消令牌；`finally` 关闭客户端；方法签名 `callTool(McpToolCall)` / `readResource(McpResourceRequest)` / `getPrompt(McpPromptRequest)`。

**`McpToolCall` / `McpResourceRequest` / `McpPromptRequest` / `McpToolResult` / `McpResourceContent` / `McpPromptResult`（`com.clara.mcp.client.call`）**
- 作用：调用入参与结果的载体。
- 使用范围：网关调用。
- 为什么需要：统一调用的输入输出结构。
- 业务细节：工具调用入参承载租户、服务端标识、快照标识、工具名、Schema 哈希、参数与调用上下文；结果承载内容与错误标记；资源请求承载资源 URI；提示词请求承载提示词名与参数。
- 技术细节：`McpToolCall` 含 `tenantId` / `serverKey` / `snapshotId` / `toolName` / `schemaHash` / `arguments`(`Map`, 只读拷贝) / `conversation`(`ConvContext`) / `call`(`CallContext`)，不可变，空白字段与 null 上下文抛 `IllegalArgumentException`；`McpToolResult` 含 `content`(`List<Map<String,Object>>`) 与 `error`；`McpResourceContent` 含 `content`；`McpPromptResult` 含 `description` / `messages`(`List<Map>`) / `error`；**MCP 服务端按租户分区，故调用入参显式携带 `tenantId`**。

### 4.6 传输与 JSON-RPC（`com.clara.mcp.transport` / `com.clara.mcp.transport.jsonrpc` / `com.clara.mcp.transport.stdio` / `com.clara.mcp.transport.http`）

**`IMcpTransport` / `IMcpTransportListener`（`com.clara.mcp.api`）/ `McpErrors`（`com.clara.mcp`）**
- 作用：传输抽象：启动、发送请求 / 通知、取消、健康检查与错误。
- 使用范围：客户端协议交互。
- 为什么需要：屏蔽 stdio 与 streamable http 的差异。
- 业务细节：启动并注册监听，异步返回响应；发送通知；按请求 id 取消；健康检查。
- 技术细节：`start(IMcpTransportListener)` / `sendRequest(JsonRpcRequest, long)`(`CompletableFuture<JsonRpcResponse>`) / `sendNotification(JsonRpcNotification)` / `cancel(String)` / `checkHealth()`；`extends Closeable`。

**`JsonRpcRequest` / `JsonRpcResponse` / `JsonRpcError` / `JsonRpcNotification`（`com.clara.mcp.transport.jsonrpc`）**
- 作用：JSON-RPC 2.0 消息载体。
- 使用范围：客户端与传输之间。
- 为什么需要：MCP 基于 JSON-RPC，统一消息结构。
- 业务细节：请求含 id / method / params；响应含 id / result 或 error；通知无 id。
- 技术细节：不可变值对象，配合 `IMcpJsonCodec` 编解码。

**`StdioMcpTransport`（`com.clara.mcp.transport.stdio`）/ `IProcessFactory`（`com.clara.mcp.api`）**
- 作用：以本地子进程方式与 stdio 服务端通信。
- 使用范围：stdio 传输的服务端。
- 为什么需要：支持本地 MCP 服务端。
- 业务细节：按注册项地址启动子进程，按行读写 JSON-RPC 消息。
- 技术细节：经 `IProcessFactory` 启动进程，标准输入输出按行编解码；关闭时销毁进程与流；后台线程排空 stderr 防阻塞。首项命令必须是绝对路径且命中允许清单；子进程环境在**继承父进程环境**的基础上叠加配置里的 `env`——清空环境会让服务端拿不到 `PATH`、`USERPROFILE` 这类变量，启动即退出，而发现阶段只能观察到 stdout 直接 EOF。

**`StreamableHttpMcpTransport`（`com.clara.mcp.transport.http`）/ `IMcpHeadersProvider`（`com.clara.mcp.api`）**
- 作用：以 streamable http 方式与远端服务端通信。
- 使用范围：http 传输的服务端。
- 为什么需要：支持远端 MCP 服务端。
- 业务细节：经 HTTP POST 发送 JSON-RPC，按 SSE 事件流接收响应。
- 技术细节：`IMcpHeadersProvider` 提供鉴权头；JSON-RPC、会话 ID、`Last-Event-ID` 与重连由本类处理；
  连接、超时、体积上限与 SSE 解析统一委托 `clara-transport` 的 `JdkHttpTransport`。

**`IMcpJsonCodec`（`com.clara.mcp.api`）/ `JacksonMcpJsonCodec` / `McpDecodedMessage`（`com.clara.mcp.transport`）**
- 作用：JSON-RPC 消息编解码。
- 使用范围：传输层。
- 为什么需要：把线格式 JSON 与消息对象互转，协议编解码集中一处。
- 业务细节：把消息对象编码为 JSON 文本、把文本解码为请求 / 响应 / 错误 / 通知。
- 技术细节：Jackson 实现；`McpDecodedMessage` 承载解码类型与内容。

## 5. 关键实现细节

### 5.1 注册 → 发现 → 目录 → 调用闭环

1. **注册**：`IMcpServerRegistry.register` 登记服务端（租户 + 服务端标识 + 传输方式（含地址 / 鉴权）+ 已审批身份）；
2. **发现**：`IMcpDiscoveryService.refresh` 经 `IMcpClientFactory` 创建客户端 → `initialize` 校验协议版本与身份 → 按能力分页拉取工具 / 资源 / 资源模板 / 提示词 → 形成 `McpServerSnapshot`；
3. **发布与状态**：`IMcpCapabilityCatalog.publish(snapshot)` 保存快照，注册状态更新为 `ACTIVE`；协议不兼容 → `INCOMPATIBLE`，身份变化 → `REVIEW_REQUIRED`；
4. **解析**：调用前 `IMcpCapabilityCatalog.resolveTool/Resource/Prompt` 按名称与期望 Schema 哈希解析能力，哈希不一致抛 `MCP_SCHEMA_MISMATCH`；
5. **调用**：`IMcpGateway.callTool/readResource/getPrompt` 经客户端发起调用并返回结果，`finally` 关闭客户端。

### 5.2 传输抽象（stdio / streamable http）

- 统一 `IMcpTransport` 接口：`start` 启动并注册监听，`sendRequest` 异步返回响应，`sendNotification` 单向通知，`cancel` 按请求 id 取消，`checkHealth` 健康检查；
- stdio：经 `IProcessFactory` 启动子进程（继承父进程环境再叠加配置 `env`），按行读写 JSON-RPC，后台排空 stderr，关闭时销毁进程；
- http：`StreamableHttpMcpTransport` 只做 MCP 协议适配（JSON-RPC、会话、`Last-Event-ID`、重连），
  连接、超时、体积上限与 SSE 解析统一委托 `clara-transport`；
- 新增传输只需新增 `IMcpTransport` 实现 + 在 `DefaultMcpClientFactory` 注册分派。

### 5.3 客户端协议与 JSON-RPC 方法映射

| 客户端方法 | JSON-RPC method | 关键结果字段 |
| --- | --- | --- |
| `initialize` | `initialize`（随后 `notifications/initialized`） | `protocolVersion` / `serverInfo{name,version}` / `capabilities{tools,resources,prompts}` |
| `listTools` | `tools/list` | `tools[]`（name/description/inputSchema）/ `nextCursor` |
| `listResources` | `resources/list` | `resources[]`（uri/name/mimeType）/ `nextCursor` |
| `listResourceTemplates` | `resources/templates/list` | `resourceTemplates[]`（uriTemplate/name/mimeType）/ `nextCursor` |
| `listPrompts` | `prompts/list` | `prompts[]`（name/description/arguments）/ `nextCursor` |
| `callTool` | `tools/call` | `content[]` / `isError` |
| `readResource` | `resources/read` | `contents[]`（兼容回退 `content`） |
| `getPrompt` | `prompts/get` | `description` / `messages[]` |

- 请求/响应以 `JsonRpcRequest` / `JsonRpcResponse` 承载，`result` 为解码后的 Map，客户端用 Jackson 转为领域对象；错误响应（`JsonRpcError`）映射为领域错误。

### 5.4 协议版本与身份校验

- 协议版本必须位于支持列表内，否则抛不兼容错误（`MCP_PROTOCOL_INCOMPATIBLE`）并将注册状态标记为不兼容；
- 已审批身份（名称 / 版本）与初始化返回身份不一致时，抛身份变化错误（`MCP_SERVER_IDENTITY_CHANGED`）并标记为需复核；
- 校验在 `discover` 阶段完成，未通过不发布快照。

### 5.5 分页与 Schema 一致性

- 分页发现受 `maxPages` 约束，超限抛 `MCP_PAGINATION_LIMIT_EXCEEDED`；
- 游标不得重复（重复抛 `MCP_PAGINATION_CURSOR_REPEATED`），防止无限分页；
- 调用前核对工具 Schema 哈希与目录一致（`MCP_SCHEMA_MISMATCH`），避免目录与运行期能力漂移。

### 5.6 错误码与分类

| 错误码 | 场景 | 分类 |
| --- | --- | --- |
| `MCP_PROTOCOL_INCOMPATIBLE` | 协议版本不在支持列表 | CAPABILITY |
| `MCP_SERVER_IDENTITY_CHANGED` | 服务端身份与已审批不一致 | AUTHORIZATION |
| `MCP_PAGINATION_LIMIT_EXCEEDED` | 分页超过最大页数 | RESOURCE_LIMIT |
| `MCP_PAGINATION_CURSOR_REPEATED` | 游标重复 | EXTERNAL_SERVICE |
| `MCP_DISCOVERY_FAILED` | 初始化 / 拉取失败、RPC 错误 | EXTERNAL_SERVICE |
| `MCP_INVALID_MESSAGE` | 响应结构非法 | EXTERNAL_SERVICE |
| `MCP_SERVER_NOT_FOUND` | 服务端未注册 / 不可用 | VALIDATION |
| `MCP_CAPABILITY_NOT_FOUND` | 工具 / 资源 / 提示词未找到 | VALIDATION |
| `MCP_SCHEMA_MISMATCH` | Schema 哈希与目录不一致 | VALIDATION |

### 5.7 租户分区与调用入参

- 服务端注册表与能力目录按「租户 + 服务端标识」分区；同一服务端标识在不同租户下是不同服务端；
- 会话上下文（`ConvContext`）不携带租户，故 `McpToolCall` 显式携带 `tenantId` 用于定位服务端；
- 调用入参其余字段（`serverKey` / `snapshotId` / `toolName` / `schemaHash`）由调用方（编排装配层的外部工具路由）提供。

### 5.8 生命周期、资源释放与并发

- 客户端「用后即关」：发现与每次调用结束后 `finally` 关闭客户端与传输；
- `close()` 幂等；关闭后再发起请求抛领域错误；
- 注册表与能力目录用并发容器，支持并发读写；发现流程中的分页收集为局部变量，无共享可变状态。

### 5.9 与对话编排的衔接

- 编排层工具往返中，**本地工具走 clara-skill，外部工具走 clara-mcp**；
- 装配层（clara-application）以组合 `IToolInvoker` 实现分派：工具名命中本地技能注册表 → 走 skill；否则按「外部工具路由」定位（租户 / 服务端 / 快照 / Schema），用当前 `ConvContext` / `CallContext` 与参数构造 `McpToolCall` → `IMcpGateway.callTool`，结果序列化为 JSON 文本回填；
- 外部工具的路由信息（工具名 → 服务端 / 快照 / Schema）由宿主在发现后登记，编排层不感知 MCP 协议细节。

### 5.10 服务端配置与本地目录识别

**配置格式**：采用 Claude Desktop / VS Code `mcp.json` 通行格式，顶层为对象 `mcpServers`，键为服务端标识，值为对象。stdio 形态：

```json
{ "mcpServers": { "processon": {
  "command": "npx",
  "args": ["-y", "@processon/mcp-server-processon"],
  "env": { "PROCESSON_API_KEY": "PNnnB..." } } } }
```

http 形态（有 `url`，可选 `headers`）：

```json
{ "mcpServers": { "remote": {
  "url": "https://host/mcp",
  "headers": { "Authorization": "Bearer xxx" } } } }
```

**字段映射**（`mcp.json` → `McpServerConfig` → `McpServerRegistration`）：

| `mcp.json` 字段 | `McpServerConfig` | 传输方式 | `McpServerRegistration` |
| --- | --- | --- | --- |
| `command`（字符串或字符串数组） | `command` 首段 | STDIO | `command`、`address`（命令行拼接） |
| `args`（字符串数组） | 追加到 `command` | STDIO | `command` |
| `env`（字符串映射） | `env` | STDIO | `env` |
| `url`（字符串） | `url` | STREAMABLE_HTTP | `url` → `address` |
| `headers`（字符串映射，可选） | `headers` | STREAMABLE_HTTP | `headers` |

传输方式推断：有 `command` 且无 `url` → STDIO；有 `url` 且无 `command` → STREAMABLE_HTTP；两者皆有或皆无 → 报错。

**目录与覆盖规则**：`McpServerConfigDirectoryLoader` 扫描运行目录（默认 `config/mcp`，相对运行目录）下递归匹配的所有 `*.json`，逐个加载并合并；可选叠加 classpath 上的内置 `config/mcp`。同名 `serverKey` 覆盖规则：**运行目录覆盖 classpath 内置；同一来源内按路径排序后「后加载覆盖先加载」**。目录不存在或无 `*.json` 时返回空；非 `*.json` 文件忽略。

**env 插值**：`env` 与 `headers` 的值支持 `${VAR}` 占位，从系统环境变量解析；**变量未定义时抛错并在消息中指明变量名**（不静默保留原文），避免把未解析的占位符当真实密钥外发。

**注册流程**：宿主读取配置 →（可选）`McpServerConfigDirectoryLoader.load()` 合并 → `McpServerRegistrar.registerAll(tenantId, configs)` 逐条转换为 `McpServerRegistration`（状态 `ACTIVE`）并 `IMcpServerRegistry.register` → 交由 `DefaultMcpDiscoveryService` 发现。`DefaultMcpClientFactory` 优先使用注册项的结构化字段：stdio 用 `command` + `env` 启动子进程，http 用 `url` + per-server `headers` 作为请求头。stdio 首项命令若是裸命令名（如 `npx` / `node`），工厂先在允许清单里按文件名（含去掉扩展名的名字）解析成绝对路径——MCP 配置惯例写裸命令名，而传输层只接受绝对路径；清单里匹配不到则原样下传，由传输层照旧拒绝。

## 6. 业务场景与功能映射

### 6.1 业务职责

为对话编排提供外部服务联动能力：以 MCP 协议统一接入外部服务端，完成注册发现与能力目录，并提供工具调用 / 资源读取 / 提示词获取的统一入口。

### 6.2 典型业务场景（业务流程 → 技术落点）

**场景一：接入并发现外部服务端**
登记服务端 → 创建客户端 → 初始化校验 → 分页发现能力 → 发布能力快照 → 状态置为可用。落点：`IMcpServerRegistry` + `DefaultMcpDiscoveryService` + `IMcpCapabilityCatalog`。

**场景二：编排层调用外部工具**
工具往返中识别为外部工具 → 解析工具描述（Schema 校验）→ 调用服务端工具 → 返回结果回填。落点：`IMcpGateway.callTool` + `McpToolCall` / `McpToolResult`。

**场景三：读取资源 / 获取提示词**
按资源 URI 读取内容、按提示词名与参数获取提示词。落点：`IMcpGateway.readResource` / `getPrompt`。

**场景四：健康检查与状态维护**
探测服务端健康、按发现结果更新注册状态（可用 / 不兼容 / 需复核）。落点：`IMcpTransport.checkHealth` + `IMcpServerRegistry.updateStatus`。

### 6.3 功能需求映射（业务 FR → 技术实现）

| 业务需求 | 实现落点 |
| --- | --- |
| FR-M1 服务端注册 | `IMcpServerRegistry` / `InMemoryMcpServerRegistry` |
| FR-M2 能力发现 | `IMcpDiscoveryService` / `DefaultMcpDiscoveryService` + `IMcpClient` / `DefaultMcpClient` |
| FR-M3 能力目录 | `IMcpCapabilityCatalog` / `InMemoryMcpCapabilityCatalog` |
| FR-M4 工具调用 | `IMcpGateway` / `DefaultMcpGateway` + `McpToolCall` / `McpToolResult` |
| FR-M5 资源读取与提示词获取 | `IMcpGateway.readResource` / `getPrompt` |
| FR-M6 传输与健康 | `IMcpTransport`（stdio / streamable http）+ `checkHealth` |
| FR-M7 服务端配置管理 | `McpServerConfigLoader` / `McpServerConfigDirectoryLoader` / `McpServerRegistrar` |

### 6.4 业务规则与决策的落点

| 业务规则 / 决策 | 落点 |
| --- | --- |
| 所有对外联动统一走 MCP 协议 | 全模块（`IMcpClient` + `IMcpTransport`） |
| 外部能力必须先发现入目录再调用 | `DefaultMcpDiscoveryService` → `IMcpCapabilityCatalog` → `IMcpGateway` |
| 协议版本不兼容标记 / 身份变化标记需复核 | `DefaultMcpDiscoveryService` + `McpErrors` |
| 分页受页数约束、游标不重复 | `DefaultMcpDiscoveryService` 分页收集 |
| 调用前核对 Schema 哈希 | `IMcpCapabilityCatalog.resolve*` + `SchemaHasher` |
| 按租户 + 服务端标识分区 | `IMcpServerRegistry` / `IMcpCapabilityCatalog` / `McpToolCall.tenantId` |
| 配置来源与覆盖规则 | `McpServerConfigDirectoryLoader` + `McpServerConfigLoader` |

## 7. 非目标

- 不做 MCP 服务端（本模块是客户端）；
- 不承载对话编排与工具往返循环（由 clara-orchestration 负责，本模块只提供外部能力入口）；
- 不涉及观测日志系统（脱敏 / 截断 / SLF4J 等）。
