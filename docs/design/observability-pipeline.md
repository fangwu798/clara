# Clara 可观测链路设计

> 状态：第一、二、三阶段均已落地（Span 契约 + 编排/工作流/MCP 埋点 + JSONL 落盘 + 指标聚合 + OTLP 导出 + 本地观测栈）。
> 目标读者：维护 Clara 的开发者、需要接入监控的宿主方。

## 1. 要解决什么问题

一条对话出错时，现有日志能回答「打印了什么」，但很难快速回答下面五个问题：

1. 这一轮经过了哪些阶段，时间花在哪？
2. 失败发生在哪一层：模型平台、传输、上下文、工具还是工作流？
3. 这一轮花了多少 token，是否发生了重试或故障转移？
4. 同类失败是否在重复发生，趋势如何？
5. 线上出问题时，能否用 traceId 还原现场？

设计上把信号拆成三层：

- **Trace**：一次请求的因果链，回答「哪里慢、哪里错」。
- **Metrics**：低基数聚合，回答「是否在变坏」。
- **Logs**：细节与原始文本，继续使用现有控制台 / 日志文件。

Trace 是事实来源；控制台展示、JSONL、Prometheus 指标与 OpenTelemetry 导出都消费同一份事件，避免多套口径。

## 2. 非目标

- 不记录提示词正文、模型回答、工具入参和密钥。
- 外部观测后端默认关闭；本地 JSONL 与 `/trace` 不依赖任何外部服务。
- 不替代现有 `IContextTraceSink` 的控制台展示；两者是互补关系。
- 不做跨进程链路传播；当前 Clara 以单进程 CLI / 嵌入式库为主。

## 3. 总体结构

```text
业务阶段
  ClaraApplication / DefaultConversationOrchestrator
  ContextAssembler / SemanticRecallCoordinator
  DefaultSummarizeCoordinator / DefaultWorkflowEngine
  ToolRoundTripCoordinator / CompositeToolInvoker
        |
        v
Telemetry (core)
  - 生成 traceId / spanId
  - 维护当前线程父 Span；跨线程用 TelemetryContext 显式传播
  - 结束时构造 TelemetryEvent
        |
        v
ITelemetrySink (core 契约)
        |
        +--> TelemetryMetrics（指标聚合，不参与采样）
        |         |
        |         +--> TelemetryHttpServer → /metrics → Prometheus → Grafana
        |
        +--> SamplingTelemetrySink（按 trace 缓冲，错误/取消/慢请求保留）
                  |
                  +--> TelemetryStore → .clara/telemetry/traces-YYYY-MM-DD.jsonl
                  +--> OtlpHttpTelemetrySink → OTel Collector → Tempo
```

依赖方向保持稳定：`core` 只定义契约，`orchestration` 只依赖契约，`application` 提供落地实现并负责装配。

## 4. 数据模型

### 4.1 TelemetryEvent

| 字段 | 含义 | 约束 |
| --- | --- | --- |
| `traceId` | 一条用户请求共享的链路标识 | 非空 |
| `spanId` | 当前阶段标识 | 非空 |
| `parentSpanId` | 父阶段标识 | 根阶段为 null |
| `name` | 阶段名，稳定且低基数 | 如 `model.call` |
| `kind` | 阶段类别 | TURN / CONVERSATION / INTENT / CONTEXT / MODEL / TOOL / WORKFLOW / OTHER |
| `startEpochMillis` | 开始时间 | epoch 毫秒 |
| `durationMillis` | 耗时 | 非负 |
| `status` | 终态 | OK / ERROR / CANCELLED |
| `errorCategory` | 错误分类 | 复用 `ErrorCategoryEnum` |
| `errorCode` | 稳定错误码 | 复用 `ErrorCodes` |
| `errorMessage` | 脱敏后的安全文案 | 截断到 240 字符 |
| `attributes` | 低敏属性 | 只读 map，值截断到 512 字符 |

属性只放计数、标识和枚举值，例如 `provider`、`model`、`input_tokens`、`tool`、`tool.route`、`messages`。**不放用户输入、文件路径、SQL、完整 URL 查询参数。**

### 4.2 一条对话的 Span 树

```text
user.turn                                  12.4s  OK
└─ conversation.turn                       12.3s  OK   provider=DEEPSEEK model=deepseek-v4-pro
   ├─ intent.plan                           1.8s  OK   mode=complex steps=4
   ├─ context.assemble                      2.1s  OK
   │  ├─ context.window                    12ms  OK   history_messages=38 window_messages=22
   │  ├─ context.skill                     35ms  OK   prompts=2 tools=18
   │  ├─ context.code_graph                 1.6s  OK   items=1 chars=820
   │  └─ context.recall                   410ms  OK   items=0 chars=0
   ├─ model.call                            4.2s  OK   input_tokens=5100 output_tokens=180
   ├─ tool.roundtrip                        2.0s  OK   round=1 calls=1
   │  └─ tool.call                          1.9s  OK   tool=code_graph tool.route=mcp
   └─ model.call                            2.3s  OK   input_tokens=6200 output_tokens=640
```

父子关系由当前线程的 Span 栈自动建立。跨线程执行（工作流并行分支、异步回调）不会自动传播，需要在线程边界用 `TelemetryContext` 显式携带 traceId / spanId；工作流并行分支已按此方式归入同一条 trace。

## 5. 埋点清单

| 阶段 | 位置 | 关键属性 |
| --- | --- | --- |
| `user.turn` | `ClaraApplication.converse` | 输入长度、尝试次数、故障转移来源/目标、最终状态 |
| `conversation.turn` | `DefaultConversationOrchestrator.converse` | conversationId、provider、model、capability、工具往返次数 |
| `intent.plan` | `DefaultConversationOrchestrator.prepareIntent` | simple/complex、步骤数、判断依据 |
| `context.assemble` | `DefaultConversationOrchestrator.buildRequest` | 消息数、工具数、窗口预算、估算 token |
| `context.window` | `ContextAssembler.assemble` | 历史条数、窗口条数、压缩后条数、保留轮数、evicted |
| `context.compress` | `ContextAssembler.assemble` / 手动压缩 | reason=window/manual、是否生成摘要、evicted |
| `context.skill` | `ContextAssembler.assemble` | 命中提示词数、工具数 |
| `context.code_graph` | `ContextAssembler.assemble` | 命中条数、字符数 |
| `context.recall` | `SemanticRecallCoordinator` | source、召回条数、候选数、过阈值数、字符数 |
| `context.overflow` | `DefaultConversationOrchestrator` | reason=context_too_long/response_too_large |
| `model.call` | `DefaultConversationOrchestrator.invokeModel` | provider、model、stage=chat/image/video/audio/compression、call 序号、输入/输出 token、首字符耗时、结束原因 |
| `tool.roundtrip` | `DefaultConversationOrchestrator.agentLoop` | 第几轮、调用数、回填消息数 |
| `tool.call` | `ToolRoundTripCoordinator.invokeSafely` | 工具名、callId、输出字符数 |
| `tool.call` 路由补充 | `CompositeToolInvoker.invoke` | `tool.route=local/mcp`、MCP server |
| `workflow.execute` | `DefaultWorkflowEngine.execute` | workflow、instance、paused、waiting_node |
| `workflow.node` | `DefaultWorkflowEngine.executeNode` | workflow、node、node_type、paused、错误分类 |
| `mcp.load` | `ApplicationBootstrap.loadMcpServers` | 加载状态、服务数 |
| `mcp.discovery` | `ApplicationBootstrap.loadMcpServers` | server、tools、错误分类 |

## 6. 状态与失败分类

Span 的 `status` 只表达阶段终态；失败原因使用现有错误模型，不另造一套：

| 场景 | category | 备注 |
| --- | --- | --- |
| 401 | `AUTHENTICATION` | 凭据无效 |
| 403 余额不足 / 无权限 | `AUTHORIZATION` | `retryable=false` |
| 429 | `RATE_LIMIT` | 可结合 `retryAfterMillis` |
| 连接失败 / 超时 | `TIMEOUT` / `EXTERNAL_SERVICE` | 传输层错误 |
| 上下文超限 | `RESOURCE_LIMIT` | 属性可带 `stage=context` |
| 响应体超限 | `RESOURCE_LIMIT` | 属性可带 `stage=response` |
| 工具执行失败 | `EXTERNAL_SERVICE` | 当前仍会回填给模型 |
| 用户取消 | `CANCELLED` | `status=CANCELLED` |
| 本库逻辑错误 | `INTERNAL` | 需要修代码 |

`TelemetrySpan.error(ClaraError)` 会同时写入 category、code 和脱敏文案；非 `ClaraException` 只记录异常类名与脱敏消息。

## 7. 存储与生命周期

- 路径：`<工作目录>/.clara/telemetry/traces-YYYY-MM-DD.jsonl`
- 写入：追加 JSON Lines，每行一个 Span；按天滚动。
- 内存：保留最近 `maxRecentSpans` 条（默认 500），供 `/trace` 即时回看。
- 清理：启动时删除早于 `retainDays`（默认 7 天）的文件；`0` 表示不清理。
- 失败：目录不可写时只保留内存记录并提示一次，不影响对话。
- 关闭：会话结束时关闭文件句柄。

写入链路：`Telemetry.finish` 生成事件后，先交给 `TelemetryMetrics` 聚合，再交给 `SamplingTelemetrySink`；
采样通过的事件同时写入 JSONL 与 OTLP（若开启）。JSONL 保持同步追加，OTLP 走异步有界队列，二者互不阻塞。

配置位于 `config/clara.yml`：

```yaml
telemetry:
  enabled: true
  maxRecentSpans: 500
  retainDays: 7
  sampleRate: 1.0
  slowTraceMillis: 10000
  otlp:
    enabled: false
    endpoint: http://localhost:4318
    headers: {}
    timeoutMillis: 5000
    serviceName: clara
    flushIntervalMillis: 1000
  prometheus:
    enabled: false
    host: 127.0.0.1
    port: 9464
```

- `sampleRate`：成功链路保留比例，取值 0~1；非法值回退 1.0。
- `slowTraceMillis`：慢链路阈值；`0` 表示关闭按耗时保留。
- `otlp`：OTLP/HTTP 导出，默认关闭；开启后 POST 到 `<endpoint>/v1/traces`。
- `prometheus`：`/metrics` 监听地址，默认关闭且只绑回环地址。

## 8. CLI 使用

- `/trace`：查看最近一轮完整 Span 树。
- `/trace list`：列出最近 5 条 traceId。
- `/trace <traceId>`：查看指定链路。
- `/usage`：在原有 token 统计后追加本次进程的 Span 指标摘要，并显示 Prometheus 端点。
- 输出包含阶段名、耗时、状态、关键属性和失败原因。

示例：

```text
链路 7f3a...  阶段 9 个  总耗时 12.4s  状态 OK
conversation.turn          12.3s  OK   provider=DEEPSEEK model=deepseek-v4-pro
├─ intent.plan              1.8s  OK   mode=complex steps=4
├─ context.assemble         2.1s  OK
│  ├─ context.code_graph    1.6s  OK   items=1 chars=820
│  └─ context.recall      410ms  OK   items=0 chars=0
├─ model.call               4.2s  OK   input_tokens=5100 output_tokens=180
└─ tool.call                1.9s  OK   tool=code_graph tool.route=mcp
```

## 9. 隐私与采样

隐私基线不变：

- 只记录计数、枚举和标识，不记录正文。
- 错误文案经过换行清理与常见凭据脱敏（Bearer / `sk-`）。
- 属性与错误文案都有长度上限，避免单条事件过大。
- `traceId`、`conversationId`、用户输入、文件路径不进入指标标签。

采样由 `SamplingTelemetrySink` 执行，位置在指标聚合之后、JSONL / OTLP 之前：

- **按 trace 决策**：Span 先按 traceId 缓冲，根 Span 结束后再决定保留或丢弃。错误与总耗时只有在链路结束后才能确定，不能按首条 Span 提前哈希。
- **异常必留**：`ERROR` / `CANCELLED` 链路 100% 保留。
- **慢链路必留**：总耗时大于等于 `slowTraceMillis` 的链路 100% 保留。
- **成功链路采样**：其余链路按 `sampleRate` 做稳定哈希采样；`1.0` 全量，`0.0` 只留异常与慢链路。
- **内存有界**：最多缓冲 256 条未完成 trace、单条最多 1024 个 Span；超限时先放行最早一条，避免异常链路导致内存无界增长。
- **指标不采样**：`TelemetryMetrics` 挂在采样器之前，token、错误率等计数始终完整。
- **导出不阻塞**：OTLP 使用容量 4096 的异步队列与批量发送；队列满时丢弃并计数，不影响对话主链路。

## 10. 指标与告警

指标内部名使用点号分层，Prometheus 暴露时点号转下划线；计数器自动追加 `_total`，直方图输出
`_bucket / _count / _sum`。

| 指标（内部名） | Prometheus 名 | 类型 | 标签 |
| --- | --- | --- | --- |
| `clara.turn.duration` | `clara_turn_duration_*` | Histogram | status |
| `clara.model.calls` | `clara_model_calls_total` | Counter | provider, model, stage, status |
| `clara.model.errors` | `clara_model_errors_total` | Counter | provider, model, stage, category |
| `clara.model.duration` | `clara_model_duration_*` | Histogram | provider, model, stage, status |
| `clara.model.tokens` | `clara_model_tokens_total` | Counter | provider, model, stage, direction |
| `clara.model.first_token_millis` | `clara_model_first_token_millis_*` | Histogram | provider, model, stage |
| `clara.context.tokens` | `clara_context_tokens_*` | Histogram | stage |
| `clara.context.compression` | `clara_context_compression_total` | Counter | reason |
| `clara.context.overflow` | `clara_context_overflow_total` | Counter | reason |
| `clara.recall.hits` | `clara_recall_hits_total` | Counter | source |
| `clara.recall.candidates` | `clara_recall_candidates_total` | Counter | source |
| `clara.tool.calls` | `clara_tool_calls_total` | Counter | tool, route, status |
| `clara.tool.duration` | `clara_tool_duration_*` | Histogram | tool, route, status |
| `clara.workflow.executions` | `clara_workflow_executions_total` | Counter | workflow, status |
| `clara.workflow.errors` | `clara_workflow_errors_total` | Counter | workflow, category |
| `clara.workflow.node.duration` | `clara_workflow_node_duration_*` | Histogram | workflow, node_type, status |
| `clara.workflow.node.errors` | `clara_workflow_node_errors_total` | Counter | workflow, node_type, category |
| `clara.mcp.loads` | `clara_mcp_loads_total` | Counter | status |
| `clara.mcp.discovery.duration_millis` | `clara_mcp_discovery_duration_millis_*` | Histogram | server, status |
| `clara.mcp.discovery.errors` | `clara_mcp_discovery_errors_total` | Counter | server, category |
| `clara.failover` | `clara_failover_total` | Counter | from, to |

暴露与查看：

- Prometheus 端点：`http://<host>:9464/metrics`（`telemetry.prometheus.enabled=true`）。
- 终端摘要：`/usage` 输出轮次、模型调用、token、首字符 P95、工具、上下文、工作流、MCP 发现与故障转移。
- 告警规则：`deploy/observability/prometheus/alerts.yml`，覆盖模型错误率、首字符 P95、上下文超限率、
  故障转移、工具错误率与 MCP 发现失败。

## 11. 实施阶段

### 第一阶段（已完成）

- core：`Telemetry`、`TelemetrySpan`、`TelemetryEvent`、`ITelemetrySink`、`TelemetryContext`、`CompositeTelemetrySink`。
- orchestration：轮次、意图、上下文装配、模型调用、工具往返埋点。
- application：JSONL 存储、配置、`/trace` 命令、启动装配。
- 隐私：默认不记录正文，错误文案脱敏。

### 第二阶段（已完成）

- 指标聚合：`TelemetryMetrics`、`MetricSeries`、`MetricHistogram`、`PrometheusMetricsFormatter`。
- 首字符响应：`FirstTokenObserver` 观察正文 / 推理 / 工具参数的首个非空片段，写入 `model.call.first_token_ms`。
- 工作流节点：`workflow.execute` 与 `workflow.node`，覆盖暂停、失败与并行分支。
- MCP：`mcp.load` 与 `mcp.discovery` 记录加载、服务数与工具数。
- 上下文：压缩模型调用单独产生 `model.call(stage=compression)`，新增 `context.overflow` 与召回候选数。
- 采样与慢请求保留：`SamplingTelemetrySink`，异常 / 取消 / 慢链路 100% 保留，指标不采样。
- `/usage` 增加指标摘要与 Prometheus 地址。

### 第三阶段（已完成）

- OTLP/HTTP 适配器：`OtlpJsonBuilder` + `OtlpHttpTelemetrySink`，异步有界队列、批量发送、超时与鉴权头。
- Prometheus 暴露：`TelemetryHttpServer` 提供 `/metrics` 与 `/healthz`。
- 统一装配：`TelemetryRuntime` 按「指标 → 采样 → JSONL / OTLP」顺序组装，并负责关闭。
- 本地观测栈：`deploy/observability` 一键启动 OTel Collector、Tempo、Prometheus、Grafana。
- 看板与告警版本化：Grafana 数据源、`Clara 可观测总览` 看板与 Prometheus 告警规则随仓库管理。
- 部署说明：`deploy/observability/README.md`。

## 12. 测试计划（已覆盖）

- core：父子关系、显式上下文恢复、状态与错误映射、重复关闭、接收端异常不影响业务。
- application：JSONL 写入、按天滚动、保留天数清理、最近 trace 过滤、配置解析与非法值回退。
- console：树形渲染、耗时格式、错误行、空记录。
- metrics：计数器 / 直方图聚合、Prometheus 文本、`/usage` 摘要。
- sampling：错误链路保留、慢链路保留、全量模式、trace 缓冲边界。
- OTLP：JSON 结构、traceId / spanId 补齐、异步导出与失败隔离。
- orchestration：完整对话 Span 树、首字符观察者、下游观察者转发。
- workflow：工作流根 Span、节点 Span、失败状态、并行分支同 trace。

## 13. 取舍与风险

- **线程模型**：用 ThreadLocal 维护当前 Span，主链路同步执行；工作流并行分支通过 `TelemetryContext` 显式传播，异步化新增路径时必须沿用该方式。
- **同步 JSONL + 异步 OTLP**：本地文件逐条追加，保证崩溃前尽量落盘；远端导出走有界队列与批量发送，避免网络抖动拖慢对话。
- **采样晚决策**：按 trace 缓冲到根 Span 结束，才能可靠保留错误与慢链路；代价是短期内存占用，已用 trace / Span 上限约束。
- **JSONL 而非数据库**：本地优先、零部署；多实例共享与检索需要 Tempo 等外部后端。
- **不记录正文**：排障信息少一些，但显著降低隐私与体积风险；需要时通过临时开关补采。
- **外部后端默认关闭**：未部署 Collector / Prometheus 时，Clara 仍可只用 JSONL 与 `/trace`。
- **与现有日志并存**：短期会有重复信息，但职责不同；稳定后可以把控制台的调试行逐步收敛到 `/trace`。