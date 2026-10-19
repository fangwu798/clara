# clara-model 重构设计：HTTP 公共层 + 请求/响应派生体系

> **2026-10 传输层补充**：模型与 MCP 共用的 HTTP / SSE 能力已收敛到独立模块 `clara-transport`，
> 通用契约位于 `com.clara.core.transport`；`clara-model` 的 `Jdk8HttpTransport` 现在只负责
> `IRequest` → `RawHttpRequest` 的适配与错误码映射。下文关于 `sse.SseParser` 的历史设计描述
> 用于保留决策背景，当前结构以代码为准。

> 状态：**设计已全部确认（33 项决策，见 §11）**。主线 7 个批次（0~6）+ 3 个独立批次（U 工具上移 / 7 错误中文化 / 8 可观测性）。
> **已落地：批次 U（公共工具上移）、批次 2（`http` 包上移 + 接口 `I` 前缀）、批次 3（配置体系重建 + YAML 注解绑定）、批次 4-1（契约接口加 `I` 前缀 + 内部枚举提升为顶层枚举 + 删除向量化能力）、批次 4-2a（`ModelRequest` / `ModelResponse` 抽象基类 + 能力请求/响应改名 + 三个枚举迁入 core）、批次 7（错误中文化）；另有批次 0（上下文拆分）、批次 1（`status` 包），以及批次 5/6 的 **chat 线与 image 线 codec 分层**。**
> **仍未完成：批次 6 的视频 / 音频线的 codec 分层与嵌套类摊平、批次 8（请求日志与可观测性）。**
> 所有批次产出的每个类型都要按 **§8.4 注释规范**补齐中文 Javadoc（Q33），与代码同批次交，不留「以后再补」。
> 范围：`clara-model` 模块；`clara-core` **保留契约层**（接口 + 流事件 + DTO 基类 + 枚举），但**实际分包以工作区现状为准**：
> `com.clara.model.api`（能力/流契约接口、流事件、请求/响应基类）、`com.clara.model.api.media`（媒体能力接口与媒体请求/响应）、
> `com.clara.kernel`（内容/请求/响应/参数 DTO；异常在 `com.clara.kernel.exception`）、`com.clara.context`（上下文）、
> `com.clara.enums`（**全部枚举**；另有归属待确认的 `ClaraException`）、`com.clara.tool`（工具声明、内容与用量）、`com.clara.utils`（公共工具）；
> 只有平台派生 DTO 与实现层建在 `clara-model`，依赖方向恒为 `model → core`（Q15）。
>
> 已确认的关键结论（影响全篇，完整 28 项见 §11）：
> 1. `request` / `response` / `status` / `http` / `codec` / `configuration` 等实现层建在 **clara-model**；契约层（接口、流事件、DTO 基类、枚举）留 `clara-core`，**分散在 `com.clara.model.api` / `api.media` / `com.clara.kernel` / `com.clara.context` / `com.clara.enums` / `com.clara.tool`**（以工作区现状为准）；
> 2. **删除** 平台专属选项接口与 `OpenAiCompatibleOptions` / `OpenAiCompatibleImageOptions` 两个扩展点接口，平台差异全部由请求子类 + 派生 codec 承担。**现状**：均已删除，槽位与引用已全部清理；
> 3. 能力基类（`ChatRequest` / `ImageRequest` / …）**可实例化**，平台子类是可选强化；
> 4. 类名与包名**一起统一**（`ImageGenerationResult` → `ImageResponse` 等）；
> 5. **接口一律以 `I` 开头**（`IChatModel`、`IHttpTransport`、`ICallSpec`…），完整对照表见
>    [model-module-class-tree.md §0.1](model-module-class-tree.md)；
> 6. **上下文拆成「会话 + 请求」两层**：`ConversationContext`（tenantId / userId / conversationId，绑定当前 chat 会话）与
>    `CallContext`（traceId / deadline / cancellationToken，**每次请求新建，traceId 必不同**）；`runId` / `instanceId` 移出上下文（Q13）；
> 7. **枚举一律定义在 `clara-core` 的 `com.clara.enums`**（Q14 的「枚举在 core」不变，**落点以工作区现状为准**），`clara-model` 内不出现任何 `enum`；嵌套枚举提升为顶层枚举后同样放 `com.clara.enums`（Q14）；
> 8. **禁止具名嵌套类 / 接口 / 枚举（含 `Builder`）**，一个类型一个文件；匿名类不受限制（Q16）；
> 9. **不留兼容代码**（Q28）：按全新格式改写，禁止旧键别名（`@JsonAlias`）、`@Deprecated` 过渡层、被取代的旧方法/旧类型、新旧并存的双入口；
>    宁可单批次改动面变大也不引入过渡态（判定口径见 §8.3）；
> 10. **不做向量化能力**（Q29）：删除 `EmbeddingModel` / `EmbeddingRequest` / `EmbeddingResponse` / `ModelCapability.EMBEDDING` / `CapabilityKind.EMBEDDING` 与 `RegisteredModel` 的嵌入端点；
> 11. **公共工具上移到 core**（Q30~Q32）：`clara-model` 的 `com.clara.model.utils` 六个类整体搬到 `clara-core` 的 **`com.clara.utils`**（`core` 的 pom 随之加 `jackson-databind`），作为独立小批（批次 U）提前做。

---

## 1. 现状速览（重构起点）

### 1.1 现有分层

| 包 | 职责 | 备注 |
| --- | --- | --- |
| `clara-core` `com.clara.model.api`（+`api.media`） | 领域契约：请求/响应/模型接口/枚举/内容类型 | 38 个类型，无任何反向依赖 |
| `clara-model` `utils` | 错误工厂 + JSON / 字符串 / 数字 / Map 工具 | **Q30~Q32：整体上移至 `clara-core` 的 `com.clara.utils`**（原先放 model，其它模块无法引用） |
| `clara-model` `configuration` | YAML 加载 + 平台配置 + 8 个 `toXxxConfig` 转换 | 依赖 `provider` 包 |
| `clara-model` `http`（原 `provider.http`，+`sse`/`impl`） | HTTP 与 SSE 传输、凭据、往返执行器 | **Q12：整体上移并改名 `com.clara.model.http`，执行器改名 `ModelInvoker`（批次 2 ✅ 已落地）** |
| `clara-model` `provider.compatible` | OpenAI 兼容协议骨架（chat + image + tool + handler） | 含平台扩展点 `OpenAiCompatibleOptions` / `...ImageOptions` |
| `clara-model` `provider.{anthropic,openai,volcengine,alibaba,deepseek}` | 平台适配 | 配置类散落在各平台包内 |
| `clara-model` `provider.media` | 媒体桥、multipart、异步任务基类 | |
| `clara-model` `runtime` | 模型注册表与能力校验 | |

> 说明：上表是**重构起点的快照**（当时契约层集中在 `com.clara.model.api`）。落地后的**实际分包**见 §2 的「`clara-core` 侧的公共包」与 [model-module-class-tree.md §0.2](model-module-class-tree.md)：
> 枚举集中在 `com.clara.enums`、上下文在 `com.clara.context`、内容/请求/响应 DTO 在 `com.clara.kernel`、错误在 `com.clara.kernel.exception`、媒体契约在 `com.clara.model.api.media`。

### 1.2 现有请求/响应类型（重构对象）

**请求**：`ChatRequest`、`ImageGenerationRequest`、`AudioTranscriptionRequest`、`SpeechSynthesisRequest`、`VideoGenerationRequest`（**不含向量化**，见 Q29）

**响应**：`ChatResponse`、`ImageGenerationResult`、`AudioTranscriptionResult`、`SpeechSynthesisResult`（视频无 Result，返回 `MediaJob`）

全部为 `final` 类、构造器校验、手写 getter，**一个能力只有一种形状，没有任何平台派生**。

### 1.3 现存的六个结构性问题（本次要解决的）

1. **平台专有字段靠弱类型通道传递**：`OpenAiCompatibleOptions.applyTo(ChatRequest, List<Map<String,Object>>)` 把平台字段塞进裸 `Map`；平台专属选项接口的子类在 codec 里被 `instanceof` 强转。编译期无保护，平台字段的实际归属散落在 adapter 里。
2. **一个能力只有一个请求类**：无法表达平台间的结构性差异，例如 Anthropic 的 `system` 是独立字段（不在 messages 里）、`max_tokens` 必填；DeepSeek 的 `prefix` 要求末条必须是 assistant。这些约束现在只能写在 codec 的 `if` 里。
3. **配置类没有公共基类**：`AnthropicChatConfig` 在 `provider.anthropic`、`OpenAiMediaConfig` 在 `provider.openai`、`VolcengineVideoConfig` 在 `provider.volcengine`；彼此无继承关系，字段命名还不一致（`maxTextChars` vs `maxPromptChars` vs `maxImages` vs `maxOutputs`）。
4. **`ProviderConfiguration` 是上帝类**：8 个 `toXxxConfig(name)` 方法，每个都内联了「平台校验 + 参数名映射」，加平台必须改它。
5. **临时状态对象混在协议包里**：`compatible.tool.ToolState`（StringBuilder 缓冲）、`compatible.handler.AsyncStreamHandle`、`compatible.tool.StreamChunk` 既不是 API 也不是协议细节，被 javadoc 标注为「不是对外 API」却仍是 public。
6. **公共 HTTP 层的定位没有在包名上体现**：`provider.http` 读起来像「某个供应商的 http」，但它服务全部平台与全部能力。

---

## 2. 目标包结构

```
com.clara.model
├── http/                                  # 【公共层】所有模型调用的唯一出口（同步 + 流式）
│   ├── IHttpTransport                     # 接口：execute(request) / execute(request, listener)
│   ├── HttpRequest / HttpResponse         # 值对象（由 impl.ProviderHttpRequest/Response 上移）
│   ├── HttpTransportConfig                # 白名单/超时/体积上限/默认头
│   ├── ICredentialResolver                # 凭据解析（原 ProviderCredentialResolver）
│   ├── ModelInvoker                       # 凭据→请求头→发送→状态码映射（原 ProviderInvoker）
│   ├── ICallSpec                          # 请求侧描述（原 ProviderRequestPlan）
│   ├── ICallExchange                      # 请求侧 + 响应解析（原 ProviderExchange）
│   ├── sse/{ISseListener, SseEvent, SseParser}
│   └── impl/Jdk8HttpTransport
│
├── request/                               # 【请求侧】基类 → 能力基类 → 兼容层 → 平台派生
│   ├── ModelRequest                       # 根基类：会话(context) + 请求(call) / modelId 覆盖 / 平台声明
│   ├── ChatRequest
│   ├── audio/{AudioTranscriptionRequest, SpeechSynthesisRequest}
│   ├── compatible/                        # OpenAI 兼容家族
│   │   ├── OpenAiCompatibleChatRequest
│   │   ├── deepseek/DeepSeekChatRequest
│   │   ├── volcengine/VolcengineChatRequest
│   │   └── alibaba/AlibabaChatRequest
│   ├── anthropic/AnthropicChatRequest
│   ├── image/
│   │   ├── ImageRequest                   # 能力基类
│   │   ├── compatible/OpenAiCompatibleImageRequest
│   │   ├── openai/OpenAiImageRequest
│   │   ├── volcengine/VolcengineImageRequest
│   │   └── alibaba/AlibabaImageRequest
│   └── video/
│       ├── VideoRequest                   # 能力基类
│       ├── volcengine/VolcengineVideoRequest
│       └── alibaba/AlibabaVideoRequest
│
├── response/                              # 【响应侧】与请求同构
│   ├── ModelResponse                      # 根基类：providerMetadata
│   ├── ChatResponse
│   ├── compatible/{OpenAiCompatibleChatResponse, deepseek/…, volcengine/…}
│   ├── anthropic/AnthropicChatResponse
│   ├── image/{ImageResponse, compatible/…, volcengine/…, alibaba/…}
│   ├── video/{VideoResponse, volcengine/…, alibaba/…}
│   └── audio/{TranscriptionResponse, SpeechResponse}
│
├── status/                                # 【临时状态】单次调用内的可变中间态，不跨调用复用
│   ├── StreamHandleState                  # 流句柄（原 compatible.handler.AsyncStreamHandle）
│   ├── ChatStreamState                    # 对话流累加状态
│   ├── ToolCallState                      # 单个工具调用的片段缓冲
│   ├── AnthropicStreamState
│   ├── ImageStreamState                   # 生图流状态（已落盘资产、已失败序号）
│   └── VideoJobState                      # 异步任务轮询中间态
│
├── configuration/                         # 【配置】平台级 + 能力级派生体系
│   ├── ProviderConfiguration              # 平台级：credential / transport / capabilities
│   ├── ProviderCapabilityConfiguration    # YAML 原始能力节点
│   ├── ProviderCredentialConfiguration
│   ├── ProviderTransportConfiguration
│   ├── config/                            # 能力配置的继承体系（§7）
│   └── resolver/                          # 配置解析，替换 8 个 toXxx（§7.3）
│
├── codec/                                 # 协议编解码（request ⇄ wire ⇄ response）：继承式平台差异
│   ├── compatible/                        # 兼容家族模板（非 final，带 protected 钩子）
│   │   ├── OpenAiCompatibleChatCodec
│   │   ├── OpenAiCompatibleChatStreamReader
│   │   ├── OpenAiCompatibleImageCodec
│   │   └── OpenAiCompatibleImageStreamReader
│   ├── deepseek/DeepSeekChatCodec
│   ├── volcengine/{VolcengineChatCodec, VolcengineImageCodec, VolcengineImageStreamReader}
│   ├── openai/OpenAiImageCodec
│   ├── alibaba/AlibabaChatCodec
│   └── anthropic/{AnthropicChatCodec, AnthropicStreamReader}
│
├── provider/                              # 模型实现（各平台适配，沿用现有包名）
│   ├── compatible/  anthropic/  openai/  volcengine/  alibaba/  deepseek/
│   └── media/                             # 媒体桥、multipart、异步任务基类
│
├── runtime/                               # 模型注册表与能力校验（本次不动）
└── （原 utils/ 已上移至 clara-core 的 com.clara.utils，Q30~Q32）
```

**`clara-core` 侧的公共包（供全仓引用，不随模块下沉；**以工作区现状为准**）**

```
com.clara.model.api       # 契约层：能力接口 / 流契约接口 / 流事件（StreamEvent、ImageGenerationEvent 在 api.media）/
                          # 请求与响应基类（ModelRequest / ModelResponse）/ IContent / MediaContent（Q15）
com.clara.model.api.media # 媒体能力接口与媒体请求/响应（ImageRequest / ImageResponse / VideoRequest / VideoResponse /
                          # 音频请求与响应 / TranscriptionSegment / ImageGenerationEvent / MediaRequestValues）
com.clara.kernel          # 内容 / 请求 / 响应 / 参数 DTO（ChatMessage / TextContent / ChatRequest / ChatResponse / ModelParameters）
com.clara.kernel.exception # 错误（ClaraError / ErrorCodes / ErrorCategory / ErrorMessages）
com.clara.context         # 上下文（ConversationContext / CallContext / CancellationToken）
com.clara.enums           # 【全部枚举】（ModelProvider / ModelCapability / CapabilityKind / Role / FinishReason /
                          # ModelModality / ModelExecutionMode / ModelMetadataSource / StreamEventType /
                          # ImageGenerationEventType / ImageStreamDeltaKind）；ClaraException 也在本包（归属待确认）
com.clara.tool            # 工具声明与内容/用量（ToolSpecification / ToolCallContent / TokenUsage）
com.clara.utils           # 公共工具（Q30~Q32）：ExceptionUtils / FinishReasonUtils / JsonNodeUtils /
                          # MapUtils / NumberUtils / StringUtils
```

**依赖方向（强约束，用测试或包级校验守住）**

```
configuration ──▶ provider ──▶ codec ──▶ http
                                  │         │
                    request/response ◀── status
                        (纯数据，不依赖 http/codec/provider)
```

- `request` / `response` / `status` 只依赖 `clara-core` 的 `kernel`（`ConversationContext`、`CallContext`、`ClaraError`）、`media`、`tool`（Q1 结论：这些包本身住在 `clara-model`，只**使用** core 的基础类型）。
- `http` 不依赖任何 `request`/`configuration`/平台包（保持原 `provider.http` / 现 `com.clara.model.http` 的干净状态）。
- `codec.compatible` 是兼容家族模板，只处理通用字段；平台差异由 `codec.<platform>` 的派生类实现（§3.5），因此 `codec.compatible` 仍不认识具体平台。
- 只有 `provider` 认识「平台 + 能力」的组合。

### 2.4 会话与请求上下文（`clara-core` 的 `com.clara.kernel`，**不随本次迁移**）

现状 `InvocationContext` 把 7 个不相关的标识揉在一起，且**缺会话维度**——`clara-memory` 用 `conversationId` 存取历史，
但模型层拿不到它，无法表达「这次请求属于哪个会话」；同时 `child()` 会**共享 traceId**，同一会话的多个请求一旦复用
上下文实例，traceId 就会重复。按 Q13 / Q22 / Q23 拆成两层，**并且不提供任何派生方法**：

```java
package com.clara.context;

/** 【会话层】绑定当前 chat 会话，跨请求稳定。 */
public final class ConversationContext {
    private final String tenantId;        // 必填（非空白），安全与数据隔离边界
    private final String userId;          // 可选，发起者
    private final String conversationId;  // 必填（非空白），与 clara-memory 的 conversationId 同源同值
    public ConversationContext(String tenantId, String userId, String conversationId);
}

/** 【请求层】一次调用的标识与生命周期，每次请求新建。 */
public final class CallContext {
    private final String traceId;             // 必填（非空白），**每次请求重新生成**，同会话内不同请求必须不同
    private final long deadlineEpochMillis;   // 本次调用的绝对截止时间
    private final CancellationToken cancellationToken;  // 必填
    public CallContext(String traceId, long deadlineEpochMillis, CancellationToken cancellationToken);
    public long remainingMillis(long now);    // 剩余毫秒，已到期返回 0
}
```

两个类型都**不可实例化的空值**：构造器在租户 / 会话 / 跟踪标识为空白、取消令牌为 null 时直接抛
`IllegalArgumentException`，把「上下文没配好」的问题挡在调用入口，而不是等到深层才 NPE。

| 关注点 | 归属 | 说明 |
| --- | --- | --- |
| `tenantId` / `userId` | **会话层** | 两者都是「谁在说话」，同一会话内不变 |
| `conversationId` | **会话层** | 与 `clara-memory` 的 `ConversationStore` / `ChatMemory` / `SemanticMemoryQuery` 复用同一个 ID，模型层从此可以直接把请求关联到会话历史 |
| `traceId` | **请求层** | 端到端观测标识，**一次请求一个值**；同一会话的多次请求必须不同（Q13 的核心诉求） |
| `deadlineEpochMillis` / `cancellationToken` | **请求层** | 取消与超时只对「本次调用」有意义 |
| `runId` / `instanceId` | ✗ **移出上下文** | 属于工作流 / 技能实例维度，不是会话也不是请求。需要它们的模块自己携带（见下面的实际落点） |
| 「子任务派生上下文」 | ✗ **不提供** | 删掉 `child(...)`（Q22）：子任务需要不同的截止时间或跟踪标识时**显式新建** `CallContext`，要改租户/会话就显式新建 `ConversationContext`。旧 `child()` 的语义（继承租户与用户、共享取消令牌、只能缩短 deadline）在调用点一眼可见，不再藏在方法里 |
| 「没有真实对话的调用」 | 自造会话 ID | `conversationId` 必填（Q23）：后台轮询、媒体任务这类没有 chat 会话的调用，用任务 ID 之类**稳定的语义值**充当会话 ID，不引入「默认会话」隐式行为 |

**请求对象如何持有**（§3.1）：

```java
public abstract class ModelRequest {
    private final ConversationContext conversation;  // 会话绑定
    private final CallContext call;                  // 本次请求的 trace / deadline / cancel
    private final String modelId;                    // 覆盖配置里的默认模型 ID
    private final ModelProvider provider;            // 显式声明平台
}
```

读取方式随之变化：取消检查从 `request.getContext().getCancellationToken()` 变为
`request.getCall().getCancellationToken()`；traceId 上报同理走 `getCall()`。

**影响面（批次 0 · ✅ 已落地）**：

| 位置 | 实际改动 |
| --- | --- |
| `com.clara.context.InvocationContext` | **已删除**，拆为 `com.clara.context.ConversationContext` + `CallContext`；全仓库 grep `InvocationContext` 无残留 |
| 上下文传递的方法签名 | 按「实际用到哪些字段」拆：只用租户/用户收 `ConversationContext`，只用 trace/deadline/cancel 收 `CallContext`，两者都要则按 `(ConversationContext conversation, CallContext call)` 顺序收两个参数。实测：`http` 层（`IHttpTransport` / `SseParser` / `Jdk8HttpTransport`）只收 `CallContext`；`ModelInvoker` 收两个（凭据要租户、往返要 call）；`ProviderMediaAssetBridge` 收两个（落盘要租户、往返要 call；该接口尚未改名为 `IProviderMediaAssetBridge`） |
| 约 14 处 `context.getTraceId()` / `getCancellationToken()` | 改走 `call.*`（`clara-model` 的 http 层、各适配器与 codec；`clara-mcp` 的取消检查；`clara-skill` 的执行器） |
| `clara-mcp` 的三个请求 DTO | 保留 `ConversationContext` + `CallContext`（`DefaultMcpGateway` 除租户校验外还做取消检查，只留 conversation 会丢掉取消行为） |
| orchestration 的 `InteractionRequest.getRunId()/getInstanceId()` | **无需改动**：它委派的是 orchestration 自己的 `InteractionContext`，与 kernel 上下文无关（原判断有误，已核实） |
| `clara-skill` 的 `SkillExecutionRequest` | runId / instanceId **落成它自己的字段**（构造器新增两个参数），`GeneralWorkspaceSkillHandler` 改读请求自有字段与 `getCall()` |
| `clara-memory` | 无需改动（它本就按 `conversationId` 工作）；接入时从 `ConversationContext` 取 ID 即可 |
| 测试 | 删除 `InvocationContextTest`，新增 `ContextsTest`（8 个用例：租户/会话/跟踪标识必填校验、`userId` 可空、取消令牌必填、`remainingMillis` 非负）；约 20 个测试文件的构造点同步为两个对象 |
| 落地前已存在的 `status.StreamHandleState` | 构造参数由 `InvocationContext` 改为 `CallContext`（它只用取消令牌），流式模型的构造点同步 |
| 验证证据 | `mvn -o test -Dmaven.surefire.version=2.22.2 -Dtest='!DeepSeekChatLiveTest' -DfailIfNoTests=false` → **BUILD SUCCESS**，各模块 `Tests run`：Core 35 / Model 75 / Memory 31 / MCP 29 / Orchestration 22 / Skill 9 / Application 51 / Novel 11，Failures 与 Errors 全 0 |

---

## 3. 请求侧设计

### 3.1 根基类

```java
package com.clara.model.api;   // 注意：根基类在 core 的契约层，不在 clara-model 的 request 包

@Getter
@EqualsAndHashCode
public abstract class ModelRequest {
    /** 会话绑定：租户、用户、会话 ID。必填，跨请求稳定。 */
    private final ConversationContext conversation;
    /** 本次请求的调用标识：traceId、截止时间、取消令牌。必填，每次请求新建。 */
    private final CallContext call;
}
```

> **现状修正（以工作区为准）**：`ModelRequest` 只保留 `conversation` + `call` 两个字段；设计期的
> `modelId` 覆盖与 `provider` 声明**两个槽位已移除**——模型 ID 由 `ModelParameters.model` 承载，
> 平台由配置 + 请求子类类型（入口 `codec.adapt(request)` 适配）决定。

> 为什么把 `call` 放进请求对象而不是方法参数：模型接口的方法签名保持 `chat(request)` / `chat(request, observer)` 两个（Q5），
> 请求对象是唯一的输入载体；拆成两个字段后，「会话」与「请求」的边界在类型上就不可混淆了。

> 说明：平台专属选项接口在新体系下由「请求子类 + 派生 codec 的 `adapt`」取代，**已删除**（类型文件、槽位与引用均已清理；见 §11-Q2 与批次 6）。

### 3.2 能力基类

```java
// 能力基类：可实例化（Q3 结论），业务方不必知道平台
@Getter
@EqualsAndHashCode(callSuper = true)
public class ChatRequest extends ModelRequest {
    private final List<ChatMessage> messages;
    private final ModelParameters parameters;
    private final List<ToolSpecification> tools;
    private final boolean clearTools;
}

@Getter
@EqualsAndHashCode(callSuper = true)
public class ImageRequest extends ModelRequest {
    private final String prompt;
    private final List<String> sourceImageAssetIds;
    private final String maskAssetId;
    private final int count;
    private final Integer width;
    private final Integer height;
    private final String aspectRatio;
    private final String quality;
    private final String style;
    private final Long seed;
    private final String negativePrompt;
}
```

`VideoRequest`、`AudioTranscriptionRequest`、`SpeechSynthesisRequest` 同构处理（字段沿用现 DTO，去掉 `providerOptions`）。

只有根类 `ModelRequest` 是 `abstract`（它本身不构成一次具体调用）；`ChatRequest` / `ImageRequest` / `VideoRequest` 这些能力基类**可实例化**，平台子类是可选强化。

### 3.3 兼容层（兼容家族的公共扩展）

```java
@Getter
@EqualsAndHashCode(callSuper = true)
public class OpenAiCompatibleChatRequest extends ChatRequest {
    /** 兼容家族的通用扩展字段 */
    private final String reasoningContent;      // 推理内容回填
    private final Boolean includeUsage;         // stream_options.include_usage
    private final Boolean stream;               // 是否要求事件流（默认 false）
    private final List<String> stopSequences;
}
```

### 3.4 平台派生

| 平台请求类 | 相对兼容层新增 | 对应现有实现 |
| --- | --- | --- |
| `OpenAiCompatibleChatRequest` | `reasoningContent`、`includeUsage`、`stream`、`stopSequences` | `OpenAiCompatibleOptions` 钩子 |
| `request.compatible.deepseek.DeepSeekChatRequest` | `prefixCompletion`（要求末条为 assistant） | `DeepSeekChatOptions` |
| `request.compatible.volcengine.VolcengineChatRequest` | `thinking`（深度思考开关） | 暂无 |
| `request.compatible.alibaba.AlibabaChatRequest` | `enableThinking`、`enableSearch` | 暂无 |
| `request.anthropic.AnthropicChatRequest` | `system`（独立字段）、`maxTokens`（必填）、`thinkingBudgetTokens` | `AnthropicChatCodec` 里的 `if` 分支 |
| `request.image.compatible.OpenAiCompatibleImageRequest` | `responseFormat`、`watermark`、`quality`、`style` | `OpenAiCompatibleImageOptions` 钩子 |
| `request.image.openai.OpenAiImageRequest` | `usesMultipartEdit`（编辑接口上传文件） | `OpenAiImageOptions` |
| `request.image.volcengine.VolcengineImageRequest` | `watermark`、`seed`、`negativePrompt`、`imageUrl` | `VolcengineImageOptions` |
| `request.image.alibaba.AlibabaImageRequest` | `outputType`、`outputMimeType` | `AlibabaNativeMediaJobFactory` |
| `request.video.volcengine.VolcengineVideoRequest` | `firstFrame` / `lastFrame` / `ratio` | `VolcengineVideoJobFactory` |
| `request.video.alibaba.AlibabaVideoRequest` | `outputType`、`outputMimeType` | `AlibabaNativeTaskConfig` |

**Q2 已确认**：删除平台专属选项接口与两个 options 扩展点接口，平台差异全部由上述请求子类 + 派生 codec 承担。

**Q3 已确认**：能力基类**可实例化**（业务方如 `clara-novel` 继续 `new ChatRequest(...)`，不必知道平台），平台子类作为**可选强化**，需要平台专属字段时才用。

### 3.5 平台差异的落点（Q2 决策后）

删除 options 扩展点后，平台差异有两个载体：**请求子类**（携带字段）与 **派生 codec**（决定字段怎么写进线格式）。

**codec 改为继承式：兼容层做模板，平台层只写差异**

```java
package com.clara.model.codec.compatible;

/** 兼容家族模板：通用字段都在这里；平台差异留给子类的钩子。 */
public class OpenAiCompatibleChatCodec {                 // 非 final
    public byte[] encode(OpenAiCompatibleChatRequest request, OpenAiCompatibleChatConfig config, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        // model / messages / tools / 采样参数 / stream / stream_options
        applyProviderFields(request, body);               // ← 钩子
        return json.write(body);
    }
    public ChatResponse decode(byte[] body, OpenAiCompatibleChatConfig config) { /* 通用解析 */ }

    /** 平台专属字段；默认空实现。 */
    protected void applyProviderFields(OpenAiCompatibleChatRequest request, Map<String, Object> body) { }
}

/** DeepSeek：把 prefix / reasoning_content 写到末条 assistant 消息。 */
public final class DeepSeekChatCodec extends OpenAiCompatibleChatCodec {
    @Override protected void applyProviderFields(OpenAiCompatibleChatRequest request, Map<String, Object> body) { … }
}
```

图片侧同理：`OpenAiCompatibleImageCodec`（模板）提供 `applyProviderFields(...)` / `usesMultipart(request)` / `validate(request)` 三个钩子，`VolcengineImageCodec`、`OpenAiImageCodec` 覆盖它们——即**现在三个 options 实现类的逻辑原样搬进各自的派生 codec**。

流式侧同样按「模板 + 派生」组织：`OpenAiCompatibleChatStreamReader` / `OpenAiCompatibleImageStreamReader` 是模板，平台派生类只覆盖「平台额外字段如何进状态」。

**平台 codec 与模型实现如何配对**（见 §11-Q10）：

| 做法 | 说明 | 优点 | 代价 |
| --- | --- | --- | --- |
| **(a) 模型通用 + 构造器注入 codec（推荐）** | `OpenAiCompatibleChatModel(config, codec, transport, credentials)`；装配处传 `new DeepSeekChatCodec()` | 类数量最少；平台差异集中在 codec 派生类 | 装配代码多一个参数 |
| (b) 模型也派生 | `provider/deepseek/DeepSeekChatModel extends OpenAiCompatibleChatModel`，在平台类里 `new DeepSeekChatCodec()` | 平台差异只有一个落点；与「基类 + 派生」风格完全一致 | 平台 × 能力 × 同步/流式 ≈ 12 个薄类 |
| (c) codec 内分派 | 兼容 codec 单类，内部按 `config.getProvider()` 分支 | 无新类 | `codec.compatible` 重新认识平台，回到方案 A/C 的老问题 |

推荐 **(a)**；若希望「加一个平台只动一个包」，选 (b)。

---

## 4. 响应侧设计

与请求同构：`ModelResponse`（根）→ 能力基类 → 兼容层 → 平台派生。

```java
@Getter
@EqualsAndHashCode
public abstract class ModelResponse {
    private final Map<String, String> providerMetadata;   // provider / apiProfile / 平台原始标识
}

@Getter
@EqualsAndHashCode(callSuper = true)
public class ChatResponse extends ModelResponse {
    private final ChatMessage message;
    private final TokenUsage tokenUsage;
    private final FinishReason finishReason;
}
```

同样地，只有 `ModelResponse` 是 `abstract`；响应基类**可实例化**，codec 返回平台子类时调用方仍按基类读取（`ChatResponse` 的现有构造点在测试与业务方保持可用）。

| 平台响应类 | 相对兼容层新增 | 现状来源 |
| --- | --- | --- |
| `response.compatible.OpenAiCompatibleChatResponse` | `id`、`model`、`systemFingerprint`、`reasoningContent` | 现在塞进 `providerMetadata` Map |
| `response.compatible.deepseek.DeepSeekChatResponse` | `reasoningContent` 提升为显式字段 | 同上 |
| `response.anthropic.AnthropicChatResponse` | `stopSequence`、`cacheCreationTokens`、`cacheReadTokens` | 现在只在 metadata |
| `response.image.ImageResponse` | `assetIds`、`requestId` | `ImageGenerationResult` |
| `response.image.compatible.OpenAiCompatibleImageResponse` | 原始 `data[]` 条目 | — |
| `response.image.volcengine.VolcengineImageResponse` | `generatedImages`、`outputTokens`、`webSearchCount` | 流式 usage 元数据 |
| `response.image.alibaba.AlibabaImageResponse` | `taskId`、`taskStatus` | `AlibabaNativeMediaJobFactory` |
| `response.video.VideoResponse` | `jobId`、`status`、`assetIds` | 现为 `MediaJob` |
| `response.audio.TranscriptionResponse` / `SpeechResponse` | 沿用现字段 | `AudioTranscriptionResult` / `SpeechSynthesisResult` |

命名变更：`ImageGenerationResult` → `ImageResponse`，`AudioTranscriptionResult` → `TranscriptionResponse`（见 §11-Q4，改名的收益是命名统一，代价是全仓 import 调整）。

**响应基类是否抽象？** 推荐**可实例化**：codec 返回平台子类，调用方按基类读取；测试里的假响应（如 `new ChatResponse(...)`）继续可用。

---

## 5. 临时状态对象：`model.status`

「临时使用」的判定标准：**单次调用内创建、单次调用内丢弃、可变、不跨线程共享（或只在读流线程与调用线程之间单向传递）**。

| 目标类 | 现状 | 内容 |
| --- | --- | --- |
| `status.StreamHandleState implements IStreamHandle` ✅ 批次 1 | `compatible.handler.AsyncStreamHandle` | `Future` 引用 + 终态标记；`cancel()` / `isDone()` |
| `status.ChatStreamState` ✅ 批次 5/6 | `OpenAiCompatibleStreamAccumulator` 的可变字段 | `StringBuilder text/reasoning`、`Map<Integer, ToolCallState>`（`TreeMap`）、`id/model/fingerprint`、`finishReason`、`usage`、`accumulatedChars`、`sawText`、`complete` |
| `status.ToolCallState` ✅ 批次 1 | `compatible.tool.ToolState` | 单个工具调用的 `id/name/arguments` 缓冲 |
| `status.AnthropicStreamState` ✅ 批次 5/6 | `AnthropicStreamAccumulator` 的可变字段 | `Map<Integer, AnthropicContentBlockState>`、`id/model/stopSequence`、`finishReason`、`inputTokens/outputTokens`、`accumulatedChars`、`started/messageDelta/complete` |
| `status.AnthropicContentBlockState` ✅ 批次 5/6 | `AnthropicStreamAccumulator.Block`（内部类） | 单块状态：`type`、`StringBuilder value`、`id/name`、`stopped` |
| `status.ImageStreamState` ✅ 批次 5/6 | `OpenAiCompatibleStreamingImageModel` 的局部变量 | 已落盘 `assetIds`、已失败 `failedIndices`、收尾 `metadata`、`requestId`、`complete` |
| `status.VideoJobState`（**未创建**，视频线未开工） | 任务轮询中的局部状态 | 提交/轮询次数、上次状态、版本号 |

拆分原则：**`status` 只放字段与 lombok 注解，不放逻辑**；读事件、更新状态、产出增量的逻辑留在 `codec`（`…StreamReader`）与 `provider` 的模型类里。因此 `status` 类的 lombok 注解允许 `@Getter/@Setter`（可变），与不可变的 `request`/`response` 形成对比。

---

## 6. HTTP 公共层

「取消检查 → 凭据解析与清零 → 组装请求头 → 发送 → 状态码映射」早已收敛到执行器；**批次 2 只做定位与命名（✅ 已落地），业务逻辑未动**。下表记录改名与上移的对照：

| 现有 | 目标 | 变更 |
| --- | --- | --- |
| `provider.http.ProviderRequestPlan<I>` | `http.ICallSpec<I>` | 改名 + 上移 |
| `provider.http.ProviderExchange<I,O>` | `http.ICallExchange<I,O>` | 改名 + 上移 |
| `provider.http.impl.ProviderInvoker` | `http.ModelInvoker` | 改名 + 上移 |
| `provider.http.ProviderHttpTransport` | `http.IHttpTransport` | 改名 + 上移 |
| `provider.http.impl.ProviderHttpRequest/Response` | `http.HttpRequest/HttpResponse` | 改名 + 上移 |
| `provider.http.ProviderCredentialResolver` | `http.ICredentialResolver` | 改名 + 上移 |
| `provider.http.event.ProviderSse{Event,Parser}` / `ProviderSseListener` | `http.sse.Sse{Event,Parser}` / `ISseListener` | 改名 + 上移 |
| `provider.http.impl.ProviderHttpTransportConfig` | `http.HttpTransportConfig` | 改名 + 上移 |

> 上表左列的旧包 `com.clara.model.provider.http` 与旧类名在批次 2 已全部删除，全仓 0 残留（左列仅作对照留存）。

同步 / 流式的两种通道保持现在的三入口语义：

```java
public final class ModelInvoker {
    /** 非流式：发送 + 状态码校验 + 解析。 */
    public <I, O> O exchange(I request, ConversationContext conversation, CallContext call,
                             ICallExchange<I, O> exchange);
    /** 流式：响应体由监听器消费，读完才返回。 */
    public <I> HttpResponse exchangeStreaming(I request, ConversationContext conversation, CallContext call,
                                              ICallSpec<I> spec, ISseListener listener);
    /** 命令式：任务轮询/取消这类不做解析的往返。 */
    public HttpResponse execute(String method, URI endpoint, String contentType, byte[] body,
                                Map<String, String> extraHeaders, ConversationContext conversation, CallContext call);
}
```

> 为什么显式传两个上下文而不从 `request` 里取：`ModelInvoker` 的类型参数是泛型 `I`（`clara-model` 的 http 层**不允许依赖** `request`/`response` 包，见 §2 依赖方向），
> 因此不能假设 `I` 是 `ModelRequest`。`ConversationContext` 提供租户（凭据解析用），`CallContext` 提供取消与超时。

请求对象到 HTTP 的路径保持不变：`RequestDTO → (codec 编码) → ICallSpec / ICallExchange → ModelInvoker → IHttpTransport`。流式与非流式的差异**不放在请求 DTO 里**，而由模型接口的两个方法表达（见 §11-Q5）。

---

## 7. 供应商配置的派生体系

### 7.1 能力配置基类树

```java
package com.clara.model.configuration.config;

@Getter
@EqualsAndHashCode
public abstract class ModelConfig {
    private final ModelProvider provider;
    private final URI endpoint;
    private final String modelId;
    private final String apiProfile;
    private final String secretRef;
}

@Getter
@EqualsAndHashCode(callSuper = true)
public abstract class ChatModelConfig extends ModelConfig {
    private final Integer defaultMaxTokens;
}

@Getter
@EqualsAndHashCode(callSuper = true)
public abstract class ImageModelConfig extends ModelConfig {
    private final URI taskQueryEndpoint;      // 异步生图为非 null
    private final int maxPromptChars;
    private final int maxOutputs;
    private final int maxSourceImages;
    private final int maxInputBytes;          // >0 表示支持 multipart 上传
}

@Getter
@EqualsAndHashCode(callSuper = true)
public abstract class VideoModelConfig extends ModelConfig {
    private final URI taskQueryEndpoint;
    private final int maxPromptChars;
    private final int maxReferenceImages;
}

@Getter
@EqualsAndHashCode(callSuper = true)
public abstract class AudioModelConfig extends ModelConfig {
    private final int maxTextChars;
    private final int maxInputBytes;
    private final int maxOutputs;
}
```

> 说明：`ImageModelConfig` 及其派生类的构造器含 `apiProfile`（值取自能力节点既有的 `apiProfile` 键），与其他能力配置的取值口径一致；该字段在批次 3 已随基类 `ModelConfig` 落地。

### 7.2 平台派生

```
ModelConfig
├── ChatModelConfig
│   ├── configuration.config.compatible.OpenAiCompatibleChatConfig   (OPENAI / DEEPSEEK / VOLCENGINE / ALIBABA 的 chat 统一由它承担)
│   │     └── ✗ 不存在 deepseek.DeepSeekChatConfig / volcengine.VolcengineChatConfig（原计划创建，实际由上一行承担）
│   └── configuration.config.anthropic.AnthropicChatConfig   (+ apiVersion、defaultMaxTokens)
├── ImageModelConfig
│   ├── configuration.config.compatible.OpenAiCompatibleImageConfig  (OPENAI / VOLCENGINE 的 image；非 final，供 OPENAI 特化)
│   │     ├── openai.OpenAiImageConfig           (+ maxInputBytes > 0，走 multipart)
│   │     └── ✗ 不存在 volcengine.VolcengineImageConfig（实际由 OpenAiCompatibleImageConfig 承担）
│   └── configuration.config.alibaba.AlibabaImageTaskConfig  (+ outputType / outputMimeType)
├── VideoModelConfig
│   ├── configuration.config.volcengine.VolcengineVideoConfig
│   └── configuration.config.alibaba.AlibabaVideoTaskConfig
└── AudioModelConfig
    ├── configuration.config.openai.OpenAiAudioConfig
    └── configuration.config.alibaba.AlibabaAudioConfig
```

> 以上为**批次 3 落地后的实际继承树**（不再是「将要创建」）：`config.volcengine.VolcengineChatConfig` / `VolcengineImageConfig` 当前**未创建**，分别由 `OpenAiCompatibleChatConfig` / `OpenAiCompatibleImageConfig` 承担；阿里侧也没有 `AlibabaChatConfig`，chat 同样走 `OpenAiCompatibleChatConfig`。

统一参数命名（顺带修掉现状里 `maxTextChars` / `maxPromptChars` / `maxImages` / `maxOutputs` 四套叫法）：配置类统一用 `maxPromptChars` / `maxOutputs` / `maxSourceImages` / `maxInputBytes`；**YAML 键名与配置字段一次性对齐，仓库内所有配置文件在同批次内同步改键名，不保留任何旧键**（见 §8.3）。

**仓库内实际改键清单**（批次 3 ✅ 实测：**改 8 处、涉及 5 个文件**；原估「10 处」偏多）：

| 旧键 | 新键 | 实际出现位置（处数） |
| --- | --- | --- |
| `maxTextChars` | `maxPromptChars` | `config/model-providers/openai.yml` 的 image 节点（1）、`examples/openai.example.yml` 的 image 节点（1） |
| `maxImages` | `maxOutputs` | `config/model-providers/volcengine.yml`（3）、`examples/volcengine.example.yml`（1） |
| `maxInputImages` | `maxSourceImages` | `examples/alibaba.example.yml`（1） |
| `maxInputImages` | `maxReferenceImages` | `examples/alibaba.example.yml`（1） |
| `maxSourceImages` / `maxInputBytes` / `maxPromptChars` / `maxOutputs` | 不变 | 已是目标命名（未计入上表处数） |

> **注意（音频节点的 `maxTextChars` 保留）**：`AudioModelConfig` 的字段名本就是 `maxTextChars`（音频侧语义是文本长度，不是图片提示词长度），因此 `examples/openai.example.yml` 与 `examples/alibaba.example.yml` 里**音频节点剩余的 `maxTextChars` 是按设计保留的，不是漏改**。

`examples/deepseek.example.yml`、`examples/anthropic.example.yml`、`deepseek.yml` 无需改动。

> 注：火山的 `watermark` 这类「平台开关」建议放**配置类**（构造函数参数）还是**请求对象**（每次调用可不同）？推荐放请求对象（`VolcengineImageRequest.watermark`），配置类只保留限制与默认值，避免同一字段两处可写（§11-Q6）。

### 7.3 配置解析：替换 8 个 `toXxx` 方法（批次 3 ✅ 已落地）

```java
package com.clara.model.configuration.resolver;

public interface IModelConfigResolver {
    /** 是否负责该平台 + 能力组合。 */
    boolean supports(ModelProvider provider, CapabilityKind kind);
    /** 从 YAML 能力节点构造具体配置对象。 */
    ModelConfig resolve(ProviderConfiguration provider, String capabilityName, CapabilityKind kind);
}
```

> `CapabilityKind` 定义在 **`clara-core` 的 `com.clara.model.api`**（Q14 硬规则），不在 resolver 包——class-tree §5.3 的子包示意图把枚举画在 `resolver/` 下是笔误。

- 平台实现（批次 3 实测组合）：
  - `OpenAiCompatibleConfigResolver`：OPENAI / DEEPSEEK / VOLCENGINE / ALIBABA **四家兼容平台的 chat**；
  - `OpenAiConfigResolver`：OPENAI 的 image / audio；
  - `VolcengineConfigResolver`：VOLCENGINE 的 image / video（**不负责 chat**）；
  - `AnthropicConfigResolver`：ANTHROPIC 的 chat；
  - `AlibabaConfigResolver`：ALIBABA 的 image / video / audio（**不负责 chat**）。
  - `VolcengineConfigResolver` / `AlibabaConfigResolver` 不覆盖 chat，是为满足注册表「平台 × 能力」组合唯一的要求，避免与 `OpenAiCompatibleConfigResolver` 重复注册。
- 注册表：`ModelConfigResolvers.forProvider(ModelProvider, CapabilityKind)`，静态不可变列表 + 类初始化期校验组合唯一，不支持时抛 `CAPABILITY_NOT_SUPPORTED`。
- 入口：`ProviderConfiguration.toConfig(String name, CapabilityKind kind)` 委托给注册表；**8 个 `toXxxConfig` 已同批次直接删除**（不留 `@Deprecated` 过渡层；实测清单：`toOpenAiCompatibleChatConfig` / `toAnthropicChatConfig` / `toOpenAiMediaConfig` / `toOpenAiImageConfig` / `toVolcengineImageConfig` / `toVolcengineVideoConfig` / `toAlibabaAudioConfig` / `toAlibabaNativeTaskConfig`），全部调用点一次性切到 resolver（见 §8.3）。`toVolcengineImageOptions` 保留，将随批次 6 与 options 类一起删。
- 收益：新增平台只加一个 resolver，不再改 `ProviderConfiguration`；**参数键名一次性统一，不保留历史别名**（见 §7.4）。

### 7.4 YAML 绑定：注解声明形状 + 严格绑定器（Q24~Q27）

**现状问题**：[ProviderYamlConfigurationLoader.java](file:///e:/workspace_self/xxxx/clara/clara-model/src/main/java/com/clara/model/configuration/ProviderYamlConfigurationLoader.java)（约 315 行）是「4 张白名单 `Set` + 逐字段 `requiredText` / `requiredInt` / `optionalBoolean` + 手工拼装构造器实参」的节点式解析：

| 代价 | 具体表现 |
| --- | --- |
| 加一个配置项要改 4 处 | 白名单 `Set`、取值分支、构造器实参、配置类字段 |
| 新增嵌套节点要再写一个解析方法 | 计划中的 `observability`（批次 8）就得单独写 `observability(JsonNode)` |
| 旧键兼容只能手写 | 「`maxTextChars` → `maxPromptChars`」这类映射散在 resolver / loader 里——**新格式直接取消这项需求**：键名一次性改齐，不做任何兼容（§8.3） |
| 配置类不知道自己的文件形状 | 校验与解析都堆在 loader，配置类只被动接收参数 |
| 白名单与实际支持漂移 | 字段删了忘删白名单、加了字段忘加白名单，都会在运行期才暴露 |

**方案：注解声明 + 一个公共严格绑定器。**

```java
// 1) 配置类自身声明 YAML 形状；绑定走 creator → 现有构造期校验 100% 保留
public final class ProviderConfiguration {
    @JsonCreator
    public ProviderConfiguration(
            @JsonProperty(value = "provider", required = true) ModelProvider provider,
            @JsonProperty(value = "credential", required = true) ProviderCredentialConfiguration credential,
            @JsonProperty(value = "transport", required = true) ProviderTransportConfiguration transport,
            @JsonProperty(value = "capabilities", required = true)
            Map<String, ProviderCapabilityConfiguration> capabilities) {
        /* 现有校验原样保留：必填、不许 null、capabilities 非空 */
    }
}

// 2) 需要保住「严格 / 安全」语义的字段用自定义 deserializer，而不是放弃校验
@JsonProperty("endpoint")
@JsonDeserialize(using = SafeHttpUriDeserializer.class)      // 必须绝对 HTTP(S)、无 userinfo、可解析
URI endpoint;

@JsonProperty("parameters")
@JsonDeserialize(using = ScalarMapDeserializer.class)        // 只允许字符串 / 数字 / 布尔标量
Map<String, Object> parameters;

@JsonProperty("apiKey")
@JsonDeserialize(using = SecretCharsDeserializer.class)      // YAML 字符串 → char[]（Jackson 对 char[] 的默认处理与内联密钥语义不一致，需显式转换）
char[] apiKey;

// 3) 不保留任何旧键别名：字段名就是唯一的 YAML 键名
@JsonProperty("maxPromptChars") int maxPromptChars;   // 旧键 maxTextChars 直接失效（加载期报「未知配置项」）

// 4) loader 退化成门面：对外签名不变（NovelAgentMain 与现有测试零改动）
public ProviderConfiguration load(Path file) {
    return binder.bind(file, ProviderConfiguration.class);
}
```

新增 `com.clara.model.configuration.StrictYamlBinder`（约 80 行）承接 loader 现在承担的**非字段级**职责：

| 职责 | 说明 |
| --- | --- |
| 文件校验 | 必须是普通 `.yml` / `.yaml` 文件、非符号链接、不超过大小上限（默认 1 MiB） |
| 严格读取 | YAMLFactory + `STRICT_DUPLICATE_DETECTION`（重复键报错）、**只允许一个 YAML 文档** |
| 未知项拒绝 | `FAIL_ON_UNKNOWN_PROPERTIES` 开启；捕获 `UnrecognizedPropertyException` 直接报「未知配置项 + 字段路径」。**不注册任何别名**（`@JsonAlias` 属兼容代码，禁止使用，见 §8.3） |
| 枚举容错 | `MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS`（`provider: deepseek` 可写小写） |
| 中文错误 | 把 Jackson 异常翻译成**带字段路径**的中文提示，与批次 7 的 `ErrorMessages` 风格一致（见下） |
| 建议 | 单一入口 `ProviderYamlConfigurationLoader.load(Path)` 保留不变，其余调用方不需要知道 binder 存在 |

**错误文案映射（集中翻译，不散落）**：

| Jackson 异常 | 中文提示（示例） |
| --- | --- |
| `UnrecognizedPropertyException` | 「未知配置项：transport.logLevel（第 12 行），可用项见文档」 |
| `MismatchedInputException`（类型不符 / 缺必填） | 「配置项 transport.connectTimeoutMillis 类型不符：应为整数」 |
| `ValueInstantiationException` | 「配置项 capability chat 无法构造：<构造器校验消息>」 |
| 其它 `JsonProcessingException` | 「配置文件解析失败：<原因>」 |

**收益**：
- 加配置项 = **加一个字段**（一处），白名单自动由注解派生，不会再漂移；
- 嵌套节点（`observability`）自动支持，不用再写解析方法；
- 旧键不再被接受：键名改错 / 没改的文件会在加载期直接报「未知配置项」，**不会静默按默认值跑偏**；
- 绑定走构造器，**现有构造期校验全部保留**——这是选择「注在运行时配置类上」而不是「另建 YAML DTO」的核心理由。

**风险与必须盯住的点**：
1. **安全校验不能变松**：`SafeHttpUriDeserializer` / `ScalarMapDeserializer` / `SecretCharsDeserializer` 三个自定义反序列化器是「用注解换掉手工解析」的前提，缺一个就会静默丢校验。
2. **错误文案来源变化（✅ 已落地）**：`ProviderYamlConfigurationLoaderTest` 原有 3 处英文断言（`"maxTextChars must be an integer"`、`"configuration file must exceed size limit"` 等）已同步为新的中文文案，并新增「不安全 URI 与非标量扩展参数」用例，**共 6 个用例全绿**。
3. **仓库内配置文件必须同批次改完**：`config/` 下所有 provider YAML 的键名与新格式对齐；因为不做兼容，"漏改即加载失败"是故意保留的安全网（比静默生效更好）。
4. **继承树与多态**：批次 3 的 `configuration.config.*` 若将来需要按 `apiProfile` 反序列化成不同子类，需引入 `@JsonTypeInfo`；当前 `capabilities` 是统一 DTO + 自由 `parameters` 表，**不需要**多态。

**`JsonNodeUtils` 的处置**（Q27 + Q31，✅ 批次 U + 批次 3 已落地）：拆成两半——配置向方法（`requiredObject` / `requiredText` / `optionalText` / `requiredInt` / `optionalBoolean` / `optionalUri` / `rejectUnknown` / `fieldSet` / `invalidConfig` / `safeHttpUri`）**已删除**；协议向 4 个方法（`textOrNull` / `strictTextOrNull` / `getIntValue` / `optionalIntValue`）**保留**（`OpenAiCompatibleChatCodec` 等编解码器在用），并已随 utils 包一起上移到 `clara-core` 的 `com.clara.utils`（批次 U）。`safeHttpUri` 迁入 `SafeHttpUriDeserializer`——它服务的是 YAML 配置绑定，属实现层，留在 `clara-model` 的 `configuration.deserialize`。

---

## 8. Lombok 使用规范、命名规范与注释规范

### 8.1 Lombok

需求 7 要求「类型都用 lombok 注解，避免冗余 getXX」。规范如下：

| 类别 | 注解 | 理由 |
| --- | --- | --- |
| `request` / `response`（不可变 DTO） | `@Getter` + `@EqualsAndHashCode`（子类 `callSuper = true`） | 只去 getter 冗余，**不加 setter**，保持现有「不可变 + 构造器校验」风格 |
| `status`（可变临时状态） | `@Getter` + `@Setter`（必要时 `@NoArgsConstructor`） | 临时状态允许可变 |
| `configuration.config`（不可变配置） | `@Getter` + `@EqualsAndHashCode(callSuper = true)` | 同 DTO |
| 协议事件/HTTP 值对象（`SseEvent`、`HttpRequest` 等） | `@Getter`（本次一并规范） | 这些类在批次 2 会被改名/搬迁，按新规范统一，不留两套写法 |
| 历史 `@Data` 类 | 凡在本次重写中被改名 / 搬迁 / 改建的类，一律 `@Data` → `@Getter` | `@Data` 会生成 setter；重写过的类不保留旧注解，避免同一体系内两种风格并存（Q28） |

**必须注意的技术冲突（重要）**：

- Lombok 的 `@SuperBuilder` / `@Builder` **绕过构造器**，直接把字段赋值给 `final` 字段，因此「构造器校验」在 Builder 路径上完全失效（现有 DTO 的 `messages 不得为空`、`width/height 必须成对`、`prompt 不得空白` 等校验会被跳过）。
- 两种处理方式：
  - **(a) 推荐**：只用 `@Getter`，保留手写构造器与校验。满足需求 7，零行为风险。
  - **(b)** 引入 `@SuperBuilder(toBuilder = true)`，把校验移到 `build()` 里（`@Builder` 的 `build()` 方法需手写 `validate()` 调用）；好处是构造可读性，代价是所有继承层级都要维护 `validate()` 链。
- 现有类里的 `@Data`：**凡本次重写触碰到的类，一律改为 `@Getter`**（不做「一半 `@Data`、一半 `@Getter`」的混用）；完全未触碰的类保持原样（Q28）。

### 8.2 命名规范

| 类别 | 规范 | 示例 |
| --- | --- | --- |
| **接口** | 一律以 `I` 开头 | `IChatModel`、`IStreamingChatModel`、`IImageGenerationModel`、`IStreamObserver`、`IImageGenerationObserver`、`IStreamHandle`、`IContent`、`IHttpTransport`、`ICallSpec`、`ICallExchange`、`ICredentialResolver`、`ISseListener`、`IModelConfigResolver`（均已落地）；`IProviderMediaAssetBridge`（**目标名，尚未改名**，现为 `ProviderMediaAssetBridge`） |
| 类 / 抽象类 / 枚举 | 不加前缀 | `ModelRequest`、`ChatRequest`、`OpenAiCompatibleChatCodec`、`ModelInvoker`、`ModelProvider`、`FinishReason` |
| **枚举的模块归属** | **一律定义在 `clara-core` 的 `com.clara.enums`**（Q14 的「枚举在 core」不变，落点以工作区现状为准）；`clara-model` 内最终不出现任何 `enum`；原嵌套枚举提升为顶层枚举后同样放 `com.clara.enums`。迁移时**直接新建顶层枚举文件**，不做「先建内部枚举再摊平」的中间态 | `FinishReason`、`ModelProvider`、`StreamEventType`、`ImageGenerationEventType`、`ImageStreamDeltaKind` |
| **类型定义形式** | **禁止具名嵌套类 / 接口 / 枚举（含 `Builder`）**，一个类型一个文件；迁移时不得把待迁类型定义成内部类。匿名类不在禁令内 | `ProviderModelMetadata.Builder` → `ProviderModelMetadataBuilder`、`StreamEvent.Type` → `StreamEventType` |
| 配置类 | 统一 `…Config` 后缀 | `OpenAiCompatibleChatConfig`、`VolcengineVideoConfig` |
| 请求 / 响应类 | 统一 `…Request` / `…Response` 后缀 | `ImageRequest`、`ImageResponse`、`TranscriptionResponse` |
| 状态类 | 统一 `…State` 后缀 | `StreamHandleState`、`ChatStreamState`、`ToolCallState` |

完整对照表（21 个接口逐个列出：旧名 → 新名 → 目标包；枚举迁移表；12 处嵌套类型摊平表）见 [model-module-class-tree.md §0.1](model-module-class-tree.md)。

**为什么枚举必须在 core**：枚举是契约的一部分（`FinishReason`、`ModelProvider` 出现在接口签名与 DTO 字段上），放在 `clara-model` 会让 `clara-core` 反向依赖 `clara-model`，形成 `core → model → core` 循环依赖，Maven 直接构建失败。这与 Q1「接口迁 model」在同一处冲突，故一并由 Q15 收敛为「契约层（接口 + 枚举 + 流事件 + DTO 基类）全留 core」。

**范围边界**：本次只改 `clara-model` 内的全部接口 + `clara-core` 里模型能力契约接口（按 Q15 **只改名、不换包**）。
`com.clara.context` / `com.clara.kernel` / `com.clara.media`（含 `media.job`）以及 `clara-memory` / `clara-mcp` / `clara-orchestration` /
`clara-skill` / `clara-application` / `clara-novel` 里约 62 个接口**保持原样**。其中 `MediaJobProviderOperation`
与 `MediaInput` 会被 `clara-model` 直接实现/使用，属于「范围内实现、范围外命名」的已知不一致；若要求全仓统一，
需要单独一轮改造（改动会扩散到 core 与全部下游模块）。

### 8.3 改写纪律：不留兼容代码（Q28）

本次是**按全新格式改写**，不是渐进式升级。所有批次都遵守同一条硬规则：

| 规则 | 含义 |
| --- | --- |
| 不保留旧键 | YAML 键名与配置字段一次性对齐，**禁止 `@JsonAlias` 等别名机制**；旧键在加载期直接报「未知配置项」 |
| 不保留旧方法 | 被取代的方法（如 8 个 `toXxxConfig`）**直接删除**，不标 `@Deprecated` 过渡，调用点在同批次内一次改完 |
| 不保留旧类型 | 被拆/被改名的类型（`InvocationContext`、三个 options 实现类、`AsyncStreamHandle`、`ToolState`…）**直接删除**，不留同名空壳、不留 re-export、不留 `// removed` 注释 |
| 不双写双读 | 不允许「新旧两套入口并存」：不存在新旧 loader/配置类/DTO 同时可用、或按开关走旧分支的写法 |
| 不保留旧命名 | 类名、包名、方法名按 §8.2 与 class-tree §0.1 一次改到位，不做「新名 + 旧名一起用」 |

**落地方式**：宁可让单批次的改动面变大，也不引入过渡态。批次 0 的 67 个文件、批次 3 的「8 个 `toXxxConfig` 一次性删除 + 仓库内配置文件同步改键名（实测 8 处 / 5 文件）」都是这条规则的应用。

**判定口径（自查三问）**：
1. 代码里是否存在「为了接受旧输入而保留的分支、别名、重载、默认值兜底」？→ 有就删。
2. 是否存在仅为过渡而存在的 `@Deprecated` 类型或方法？→ 直接删。
3. 是否存在两套并存的入口或两种数据形状？→ 合并为一套。

**明确不属于兼容代码（不用删）**：
- 领域术语 `compatible`：`request.compatible` / `response.compatible` / `codec.compatible` / `provider.compatible` 指的是 **OpenAI 兼容协议家族**，与「过渡兼容」无关；
- 保留对外入口的**签名**不变（如 `ProviderYamlConfigurationLoader.load(Path)`）：这是减少无谓的调用点改动，不是兼容层——但入口内部只能有一条实现路径（`load(Path)` → `StrictYamlBinder`），不得既走旧手工解析又走注解绑定；
- `clara-core` 的 `com.clara.model.api` **包名**不变（Q15），但包内类型按新格式改建。

### 8.4 注释规范：每个重新生成的类都要写全（Q33）

**范围**：本次迁移产出（新建 / 改名 / 搬迁 / 重写）的**每一个类型**，`clara-core` 与 `clara-model` 两侧一视同仁，与代码**同批次**交付。未触碰的类不强制回补注释。

#### 8.4.1 类型级：必须写清「是什么」+「怎么用」

| 必写内容 | 说明 |
| --- | --- |
| **是什么 / 在哪一层** | 职责一句话；属于契约层还是实现层；谁创建它、谁调用它（如「由 `OpenAiCompatibleChatModel` 构造器注入」） |
| **使用方法** | ① 实例从哪来（谁 new / 由谁注入 / 从哪个 resolver 获取）；② **典型调用序列**（步骤编号或行内代码片段，含前置与收尾）；③ 是否需要 `close()` / 有无资源释放 |
| **生命周期与线程安全** | 是否可变、可否跨线程共享、是否随某对象存活（如「随所属 `ProviderConfiguration`」） |
| **失败语义** | 什么条件下抛什么（异常类型 + 错误码 + `ErrorCategory`）、是否可重试、失败后状态是否可用 |
| **配置类额外项** | 类级给出**最小可用 YAML 片段**（含键名与注释说明默认值）；标明由哪个 resolver 产出 |

「复述类名」的注释视为不合格，例如 `/** OpenAiConfig 配置类。 */`、`/** 工具类。 */`。

#### 8.4.2 字段与枚举常量

必须写：语义 + **单位与取值范围** + **是否可空** + **默认值及其来源**（YAML / 构造器 / 平台约定）；配置类字段额外标出**对应 YAML 键名与必填性**；枚举每个常量至少一行，说明含义与使用场合（调用方据此做什么分支）。

```java
/** 单次请求最多产出的图片数，可选，默认 {@code 1}（见 {@code openai.yml} 同名键），取值 ≥ 1。 */
private final int maxOutputs;

/** 凭据明文，非空；日志与异常中一律不得输出（脱敏由 {@code RequestLogEmitter} 统一处理）。 */
private final char[] secret;
```

#### 8.4.3 方法与构造器

| 标签 | 要求 |
| --- | --- |
| 首句 | 一句话说明「做什么」；非自明的方法补前置条件与副作用（网络 IO、状态变更、是否可重入） |
| `@param` | 每个参数都写；**空值语义必须明确**（如「可为 null，为 null 时按平台默认值处理」） |
| `@return` | 说明返回值语义；**可能返回 null 时要写**；返回集合要说明是否可变、是否只读视图 |
| `@throws` | 写明**什么条件下**抛、抛什么（`ClaraException` 时给出错误码与分类） |

私有方法：仅在逻辑非自明时写（如位运算、协议特例），避免噪音；包私有成员在承担关键逻辑时同样要写。

#### 8.4.4 接口与抽象类

- **接口**：类级写「实现方必须保证的语义」+「调用方按什么顺序使用」（如「先 `start` → 多次 `onEvent` → 终态 `onComplete`/`onError` 二选一」）；方法级写契约与空值/异常约定，不写实现细节。
- **抽象类**：类级写「子类必须实现什么、允许覆写什么、覆写时不得破坏的约束」（如「`encode()` 返回的 `Map` 不得为 null」）。

#### 8.4.5 其他约定

- 注释语言为**中文**；类型名、方法名、包名、YAML 键名、协议字段名保留英文原文。
- 重写触碰到的旧类：注释**一并按新格式重写**，不复用与旧实现绑定的描述、不留 `@deprecated` 段、不留 `// removed` 注释（与 §8.3 一致）。
- 不写 `TODO` 占位；不保留被注释掉的代码；不写「以后可能用到」的预告式说明。
- `{@link}` 只指向**确实存在且同模块可解析**的类型——搬迁后会跨模块失效的 `{@link}` 必须改成 `{@code}` 文字描述（如 §7.4 中 `FinishReasonUtils` 对模型实现类的引用）。

#### 8.4.6 模板（逐个类型套用）

```java
/**
 * OpenAI 兼容协议的对话编解码器。
 *
 * <p>用途：在 {@code provider.compatible} 的模型实现与平台 HTTP 协议之间做双向转换。
 * 本类无状态，可被多线程共享；同一请求内重复调用结果一致。
 *
 * <p>使用方法：
 * <ol>
 *   <li>由具体模型实现的构造器注入（不自行 new，便于平台子类替换）；</li>
 *   <li>发送前调用 {@link #encode(ChatRequest)} 得到请求体；</li>
 *   <li>流式场景把 SSE 每帧交给 {@link #decodeStreamChunk(String)}，非流式把响应体交给 {@link #decode(String)}；</li>
 *   <li>无资源需要释放，无需 close。</li>
 * </ol>
 *
 * <p>失败语义：协议字段缺失或类型不符时抛 {@code ClaraException}（错误码 {@code MODEL_PROTOCOL_*}，
 * 分类 {@code ErrorCategory.PROTOCOL}），不可重试；重试判定由调用方按分类完成。
 */
public final class OpenAiCompatibleChatCodec { ... }
```

完整的三例（编解码器 / 平台配置类 / 枚举）见 [model-module-class-tree.md §0.1](model-module-class-tree.md) 规则五。

---

## 9. 现有类 → 目标类 映射（迁移清单）

### 9.1 请求

| 现有（`com.clara.model.api[.media]`） | 目标 |
| --- | --- |
| `ChatRequest` | `com.clara.kernel.ChatRequest`（基类；`providerOptions` 槽位已去掉） |
| — | `request.compatible.OpenAiCompatibleChatRequest`（新增） |
| — | `request.compatible.deepseek.DeepSeekChatRequest`（新增，替代 `DeepSeekChatOptions`） |
| — | `request.anthropic.AnthropicChatRequest`（新增） |
| `EmbeddingRequest` | ✗ **不做**（Q29：向量化能力整体移除，类型一并删除） |
| `ImageGenerationRequest` | `com.clara.model.api.media.ImageRequest`（基类，改名后的能力基类在 core）+ `request.image.*` 平台子类（新增） |
| `VideoGenerationRequest` | `com.clara.model.api.media.VideoRequest`（基类在 core）+ 平台子类 |
| `AudioTranscriptionRequest` / `SpeechSynthesisRequest` | `com.clara.model.api.media.*`（core；**没有平台子类**） |
| 平台专属选项接口 | **已删除**（Q2 结论），平台差异由请求子类 + 派生 codec 的 `adapt` 承担；类型、槽位与引用均已清理 |

### 9.2 响应

| 现有 | 目标 |
| --- | --- |
| `ChatResponse` | `response.ChatResponse` + `response.compatible.*` / `response.anthropic.*` 子类 |
| `ImageGenerationResult` | `response.image.ImageResponse`（改名，+ 平台子类） |
| `AudioTranscriptionResult` / `SpeechSynthesisResult` | `response.audio.TranscriptionResponse` / `SpeechResponse`（改名） |
| `EmbeddingResponse` | ✗ **不做**（Q29：向量化能力整体移除，类型一并删除） |
| — | `response.video.VideoResponse`（新增） |
| `ChatMessage` / `IContent` / `TextContent` / `ToolCallContent` / `MediaContent` / `TokenUsage` / `FinishReason` / `Role` | **留在 core**（Q15 覆盖了原 Q11 的 `message.*` 方案：这些类型被能力接口签名引用，迁出会造成 core→model 循环依赖）；**实际分包**：`ChatMessage` / `TextContent` 在 `com.clara.kernel`，`IContent` / `MediaContent` 在 `com.clara.model.api`，`ToolCallContent` / `TokenUsage` 在 `com.clara.tool`，`FinishReason` / `Role` 在 `com.clara.enums` |

### 9.3 状态与配置

| 现有 | 目标 |
| --- | --- |
| `com.clara.model.utils.ExceptionUtils` / `FinishReasonUtils` / `MapUtils` / `NumberUtils` / `StringUtils` / `JsonNodeUtils` | **上移至 `clara-core` 的 `com.clara.utils`**（批次 U ✅ 已落地，纯包搬迁 + 改 import；`JsonNodeUtils` 同时在批次 3 ✅ 按 Q27 删掉配置向方法、只留协议向 4 个方法；`FinishReasonUtils` 去掉对 `OpenAiCompatibleStreamAccumulator` 的 Javadoc 引用；`clara-core` 的 pom 加 `jackson-databind`） |
| `compatible.handler.AsyncStreamHandle` | `status.StreamHandleState` |
| `compatible.tool.ToolState` | `status.ToolCallState` |
| `compatible.tool.StreamChunk` | `codec.compatible.ChatStreamDelta`（增量产物归 codec，见 §11-Q9） |
| `compatible.OpenAiCompatibleStreamAccumulator` 的可变字段 | `status.ChatStreamState`；读流逻辑 → `codec.compatible.OpenAiCompatibleChatStreamReader` |
| `AnthropicStreamAccumulator` 的可变字段 | `status.AnthropicStreamState`；读流逻辑 → `codec.anthropic.AnthropicStreamReader` |
| `compatible.config.OpenAiCompatibleChatConfig` | `configuration.config.compatible.OpenAiCompatibleChatConfig extends ChatModelConfig` |
| `compatible.image.OpenAiCompatibleImageConfig` | `configuration.config.compatible.OpenAiCompatibleImageConfig extends ImageModelConfig` |
| `anthropic.AnthropicChatConfig` | `configuration.config.anthropic.AnthropicChatConfig extends ChatModelConfig` |
| `openai.OpenAiMediaConfig` | 拆为 `configuration.config.openai.OpenAiAudioConfig extends AudioModelConfig` |
| `volcengine.VolcengineVideoConfig` | `configuration.config.volcengine.VolcengineVideoConfig extends VideoModelConfig` |
| `alibaba.AlibabaNativeTaskConfig` | 拆为 `AlibabaImageTaskConfig` / `AlibabaVideoTaskConfig` |
| `alibaba.AlibabaAudioConfig` | `configuration.config.alibaba.AlibabaAudioConfig extends AudioModelConfig` |
| `compatible.OpenAiCompatibleOptions` | **已删除**（批次 5/6），逻辑并入 `codec.compatible.OpenAiCompatibleChatCodec` 的钩子与各平台派生 codec |
| `compatible.image.OpenAiCompatibleImageOptions` | **已删除**（批次 5/6），逻辑并入 `codec.compatible.OpenAiCompatibleImageCodec` 的钩子与各平台派生 codec |
| `deepseek.DeepSeekChatOptions` | **已删除**（批次 5/6），逻辑并入 `codec.deepseek.DeepSeekChatCodec` |
| `openai.OpenAiImageOptions` / `volcengine.VolcengineImageOptions` | **已删除**（批次 5/6），逻辑并入 `codec.openai.OpenAiImageCodec` / `codec.volcengine.VolcengineImageCodec` |
| `com.clara.context.InvocationContext` | **删除**，拆为 `com.clara.context.ConversationContext`（会话）+ `CallContext`（请求）；`runId` / `instanceId` 移出（批次 0 ✅ 已落地，见 §2.4） |
| `ProviderYamlConfigurationLoader` | **保留类名与 `load(Path)` 入口**，内部退化为门面（单一实现路径 → `StrictYamlBinder`）；4 张白名单 `Set` 与逐字段解析方法删除（批次 3 ✅ 已落地，见 §7.4） |
| `JsonNodeUtils` 的配置向方法（`requiredObject` / `requiredText` / `optionalText` / `requiredInt` / `optionalBoolean` / `optionalUri` / `rejectUnknown` / `fieldSet` / `invalidConfig` / `safeHttpUri`） | ✗ **删除**（Q27，随批次 3 ✅ 的注解绑定一起）；协议向 4 个方法保留在 `com.clara.utils`（批次 U ✅）；`safeHttpUri` 迁入 `configuration.deserialize.SafeHttpUriDeserializer` |
| `com.clara.model.configuration` 的各配置类 | 加 `@JsonCreator` + `@JsonProperty(required = true)` 声明 YAML 形状，并按 Q28 把 `@Data` 改为 `@Getter`（批次 3 ✅ 已落地，见 §7.4） |

> 上表中所有 `configuration.config.*` 的改建与 `ProviderYamlConfigurationLoader` 门面化均属**批次 3，✅ 已落地**；实测继承树为：`OpenAiCompatibleChatConfig`（四家 chat）/ `AnthropicChatConfig` / `OpenAiCompatibleImageConfig`（非 final，含 `OpenAiImageConfig`）/ `AlibabaImageTaskConfig` / `VolcengineVideoConfig` / `AlibabaVideoTaskConfig` / `OpenAiAudioConfig` / `AlibabaAudioConfig`（`VolcengineChatConfig` / `VolcengineImageConfig` / `AlibabaChatConfig` 未创建）。

### 9.4 不改动

`com.clara.model.runtime/*`（注册表、能力校验）、`com.clara.model.provider.media.*`（媒体桥、multipart、异步任务基类）、`http` 层的业务逻辑。

> YAML 的**字段语义**不扩张（不新增业务配置项），但键名在批次 3 一次性对齐（仓库内配置文件同步改，不做旧键兼容，见 §8.3）；批次 8 再新增 `observability` / `transport.logLevel`。

---

## 10. 迁移步骤（每步可编译、可测）

**三条贯穿全部批次的红线（Q14 / Q16 / Q33）**：

- 枚举一律**直接新建为 `clara-core` 的顶层枚举文件**，位置 **`com.clara.enums`**（Q14 的「枚举在 core」不变，落点以工作区现状为准，不是 `com.clara.model.api`）；不得在迁移中先把枚举写进某个类里当内部枚举再摊平，也不得新建任何 `clara-model` 的 `enum`。
- 禁止具名嵌套类 / 接口 / 枚举（含 `Builder`），一个类型一个文件；12 处嵌套类型的摊平在**遇到即摊平**，不留中间态。匿名类不受限制。
- 每个**重新生成的类**都要写全中文 Javadoc（类级「是什么 + 怎么用」+ 字段/方法说明），与代码同批次交，判定标准与模板见 §8.4。

**批次顺序的约束（重要）**：Q15 把「契约层（接口 + 枚举 + 流事件 + DTO 基类）」固定在 `clara-core` 的 `com.clara.model.api`，只有平台派生 DTO 与实现层进 `clara-model`，依赖方向恒为 `model → core`，不存在循环。因此批次 4 仍须**同一次提交内完成**：先就地改建 core 侧的 DTO 基类与枚举，再在 `clara-model` 新建派生 DTO 并同步切换全部调用点；若拆到不同提交，过渡期会出现两套同名不同包的 DTO，类型互不兼容，且 core 侧旧类无法先行删除。

```
批次 U  公共工具上移（clara-model/utils → clara-core 的 com.clara.utils）  ← 纯搬迁，最先做（Q30~Q32）
        ↓
批次 0  拆上下文（ConversationContext + CallContext）           ← 改 kernel，牵动全部模块
        ↓
批次 1  status          ─┐
批次 2  http            ─┼─ 相互独立，可任意顺序先行
批次 3  configuration   ─┘   （config 继承树 + resolver，不依赖 DTO）
                     ↓
批次 4-1 契约接口加 `I` 前缀 + 内部枚举提升为顶层枚举 + 删除向量化能力
                     ↓
批次 4-2a core 就地改建基类 + model 新建平台派生 DTO；请求/响应改名；三个枚举迁入 core
                     ↓
批次 5/6  codec 模板 + 平台派生 codec（chat 线、image 线）
                     ↓
批次 6  （剩余）视频 / 音频线 codec 分层与嵌套类摊平
                     ↓
批次 7  错误提示中文化（§13）                    ← 独立，不依赖 0~6，可最先做
批次 8  请求日志与可观测性（§14）                 ← 依赖批次 2 的 http 层命名与批次 0 的 CallContext
```

| 批次 | 内容 | 验证 |
| --- | --- | --- |
| U ✅ 已落地 | **公共工具上移**（Q30~Q32）：`clara-model` 的 `com.clara.model.utils` 六个类（`ExceptionUtils` / `FinishReasonUtils` / `JsonNodeUtils` / `MapUtils` / `NumberUtils` / `StringUtils`）已整体搬到 `clara-core` 的 `com.clara.utils`；`clara-core` 的 pom 加 `jackson-databind`（`JsonNodeUtils` 需要，版本沿用根 pom 的 `2.13.4`）；`FinishReasonUtils` 去掉对 `OpenAiCompatibleStreamAccumulator` 的 Javadoc `{@link}` 与 import（该文件本身是纯函数），并规范为 `final` + 私有构造器；旧包 `com.clara.model.utils` 已删除 | ✅ 全量单元测试 + 编译全部模块。**实测：6 个类上移 + core 加 `jackson-databind`、模型侧 35 处 import 改齐（其它模块 0 处）**，纯搬迁零逻辑改动 |
| 0 ✅ 已落地 | 拆上下文（§2.4）：新建 `com.clara.context.ConversationContext`（tenantId / userId / conversationId，后两者校验见 Q23）与 `CallContext`（traceId / deadline / cancellationToken），**删除** `InvocationContext` 与 `child()`（Q22）；`runId` / `instanceId` 移出上下文（唯一落点：`SkillExecutionRequest` 自有字段）；按「实际用到哪些字段」拆全部方法签名，共 67 个文件 | ✅ 全量单元测试 + 编译全部模块（Core 35 / Model 75 / Memory 31 / MCP 29 / Orchestration 22 / Skill 9 / Application 51 / Novel 11，全绿） |
| 1 ✅ 已落地 | 建 `status` 包：纯搬迁 `StreamHandleState`（原 `AsyncStreamHandle`，构造参数已是 `CallContext`）与 `ToolCallState`（原 `ToolState`），删除协议包里的旧类。**`ChatStreamState` / `AnthropicStreamState` / `ImageStreamState` 推迟到批次 5**——它们要从累加器里抽字段，而累加器在批次 5 才拆成「读流器 + 状态」，现在抽会改两遍 | 全量单元测试（75 项） |
| 2 ✅ 已落地 | 建 `http` 包：`provider.http.*`（含 `impl` / `event`）整体上移并改名为 `com.clara.model.http`（纯搬迁，逻辑不动）；同时按 Q12 把接口改成 `I` 前缀：`IHttpTransport` / `ICallSpec` / `ICallExchange` / `ICredentialResolver` / `ISseListener`，执行器改名 `ModelInvoker`、`ProviderInvoker.RawPlan` 摊平为包私有顶层 `RawCallSpec` | ✅ 全量单元测试（3 个 http 测试同步搬迁改名）。**实测：旧包与旧类名全仓 0 残留；唯一签名调整是 `ICredentialResolver.resolve(ConversationContext, String secretRef)`（原 `resolve(String tenantId, String secretRef)`）；`clara-novel` 的 3 个引用文件（`ForbiddenRequestLoggingTransport` / `NovelAgentMain` 及其测试）做最小机械改动** |
| 3 ✅ 已落地 | 建 `configuration.config` 继承体系（旧配置类改为新体系的子类）+ `IModelConfigResolver` 等 resolver，**8 个 `toXxxConfig` 在同批次直接删除**（不留 `@Deprecated`、不与 resolver 并存，见 §8.3）；**同时把 YAML 解析改为注解绑定**（§7.4）：配置类加 `@JsonCreator` + `@JsonProperty`，新增 `StrictYamlBinder` 与 3 个自定义反序列化器，`ProviderYamlConfigurationLoader` 退化为门面（`load(Path)` 签名不变、内部单一实现路径），删除 `JsonNodeUtils` 的配置向方法与 4 张白名单 `Set`；**`config/` 下 provider YAML 的键名同批次改齐**（旧键直接失效） | ✅ 全量单元测试 + 配置加载测试（未知项拒绝、旧键报错、安全 URI / 标量表 / 密钥三类校验的中文错误文案），**6 个用例全绿**。**实测：新建 config 继承树与 resolver、注解绑定落地、YAML 实际改键 8 处 / 5 个文件（含 `maxInputImages` → `maxSourceImages` / `maxReferenceImages`；音频节点的 `maxTextChars` 保留）、loader 退化为门面** |
| 4-1 ✅ 已落地 | **契约接口加 `I` 前缀 + 内部枚举提升为顶层枚举 + 删除向量化能力**：`IChatModel` / `IStreamingChatModel` / `IImageGenerationModel` / `IStreamingImageGenerationModel` / `IAudioTranscriptionModel` / `ISpeechSynthesisModel` / `IVideoGenerationModel` / `IStreamObserver` / `IImageGenerationObserver` / `IStreamHandle` / `IContent` 全部落位；`StreamEvent.Type` / `ImageGenerationEvent.Type` 提升为顶层枚举（**现落 `com.clara.enums`**）；删除向量化（`EmbeddingModel` / `EmbeddingRequest` / `EmbeddingResponse`、`ModelCapability.EMBEDDING`、`RegisteredModel` 的嵌入端点与 4 参构造器、相关测试用例） | ✅ 全量单元测试。**实测：Core 34 / Model 76，其余模块不变，全绿** |
| 4-2a ✅ 已落地 | **core 就地改建基类 + model 新建平台派生 DTO**：新增抽象基类 `ModelRequest`（`conversation` + `call`）与 `ModelResponse`（平台元数据），能力请求/响应基类继承之；`ImageGenerationRequest` → `ImageRequest`、`ImageGenerationResult` → `ImageResponse`、`AudioTranscriptionResult` → `TranscriptionResponse`、`SpeechSynthesisResult` → `SpeechResponse`；`ModelModality` / `ModelExecutionMode` / `ModelMetadataSource` 从 `runtime` 迁入 core。**注意**：平台选项槽位当时仍保留（已在批次 6 删除） | ✅ 全量单元测试 + 编译全部模块 |
| 5/6 · chat ✅ 已落地 | 新增 `com.clara.model.codec.compatible.OpenAiCompatibleChatCodec`（非 final 模板 + 钩子 `applyProviderFields` / `streamReader` / `adapt`）、`OpenAiCompatibleChatStreamReader`、`ChatStreamDelta`、`ToolDelta`、`OpenAiCompatibleReasoning`；平台派生 `codec.deepseek.DeepSeekChatCodec`、`codec.volcengine.VolcengineChatCodec`、`codec.alibaba.AlibabaChatCodec`、`codec.anthropic.AnthropicChatCodec` + `AnthropicStreamReader` + `AnthropicStreamDelta`；状态类 `status.ChatStreamState` / `AnthropicStreamState`（含 `AnthropicContentBlockState`）；删除 `OpenAiCompatibleStreamAccumulator` / `OpenAiCompatibleOptions` / `OpenAiCompatibleChatCodec`(旧) / `DeepSeekChatOptions` / `AnthropicStreamAccumulator` / `AnthropicStreamChunk` / `StreamChunk` / `ToolDelta`(旧) 等；4 个对话模型改为**构造器注入 codec**，入口调用一次 `codec.adapt(request)` 完成「基类请求 → 平台派生请求」适配（`instanceof` 只出现在这一处）。**偏差**：`streamReader` 实为 public 且多带 `maxToolCalls` / `maxAccumulatedChars`；入口适配落在各平台 codec 的 `adapt`（由模型入口调用） | ✅ 全量单元测试 + 同步流式一致性测试 |
| 5/6 · image ✅ 已落地 | 新增 `codec.compatible.OpenAiCompatibleImageCodec`（JSON 与 multipart 两条编码路径 + 钩子 `applyProviderFields` / `usesMultipart` / `validate`）、`OpenAiCompatibleImageStreamReader`、`ImageCodecOutput`、`ImageCodecPayload`、`ImageStreamDelta`；`codec.openai.OpenAiImageCodec`、`codec.volcengine.VolcengineImageCodec`、`codec.volcengine.VolcengineImageStreamReader`；状态类 `status.ImageStreamState`；`ImageStreamDeltaKind` 枚举落 `com.clara.enums`；删除旧 `OpenAiCompatibleImageCodec` / `OpenAiCompatibleImageStreamAccumulator` / `OpenAiCompatibleImageStreamPlan` / `OpenAiCompatibleImageOptions` / `OpenAiImageOptions` / `VolcengineImageOptions` 与 `ProviderConfiguration#toVolcengineImageOptions`；图片模型改为构造器注入 codec，编码/解析后由 codec 组装**平台派生响应**。**偏差**：`decode` 返回 `ImageCodecPayload`（解析结果），落盘后的响应由 codec 的 `response(payload, assetIds, config)` 组装（落盘是模型职责，故不是设计稿写的 `decode → ImageResponse`）；`usesMultipart` / `validate` 为 public（跨包调用，语义等同 protected 扩展点） | ✅ 全量单元测试 + 生图实时用例 |
| 6（部分落地） | ✅ **删除平台专属选项接口与所有请求类的 `providerOptions` 槽位**（平台字段已由派生请求承载）：类型文件、各 chat/image/video 派生请求的构造参数、codec 的 `adapt` 与模型类中的槽位读取调用均已清理；**视频 / 音频线的 codec 分层与嵌套类摊平**（`AlibabaNativeMediaJobFactory.Operation` / `VolcengineVideoJobFactory.Operation` / `handler` 事件发射器合并等）仍未开工 | 全量单元测试 + 实时用例（对话 4 项、生图 2 项） |
| 7 ✅ 已落地 | 错误提示中文化（§13）：core 新增 `ErrorMessages`（102 个错误码全部登记中文模板 + 单遍插值）；`ClaraError` 兜底消息改为「为空**或等于 code** 时取 `ErrorMessages.of(code)`」；`ExceptionUtils` 传入平台/平台码变量；`MediaJobProviderException` 透传中文消息 | ✅ 单元测试（反射遍历 `ErrorCodes` 断言逐条已登记 + 未登记码回退）+ 全量单元测试（新增 9 项） |
| 8 | 请求日志与可观测性（§14）：core 加 `RequestLogEntry` / `RequestLogStage` / `RequestLogLevel` / `RequestLogConfig` 与 `LOG` 事件；`CallContext` 加可空 `logObserver`；clara-model 加 `RequestLogEmitter` 并接到 `ModelInvoker` / SSE 解析；YAML 加 `observability` 节点与 `transport.logLevel`；pom 加 `slf4j-api` | 单元测试（关/开、BASIC/VERBOSE、脱敏、截断、未开启时零事件）+ 实时用例（对话 4 项） |

> 实时用例（`DeepSeekChatLiveTest`）在批次 4（4-1/4-2a）与批次 6 各跑一次，作为「真实平台未回归」的证据。
> 批次 4 影响面最大（实测 `com.clara.model.api.*` 被 100 个文件、338 处引用），必须单独成一个提交，便于回滚；实际拆成 4-1（接口改名 + 枚举提升 + 删向量化）与 4-2a（基类 + 派生 DTO + 改名）两个子批。
> 批次 3 与批次 4 有交集（`resolver` 产出 `configuration.config.*`，而 `request` 侧还要读平台开关）：批次 3 先落配置侧，批次 4 只碰 DTO，互不阻塞。

---

## 11. 决策记录与待确认问题

### 11.1 已确认（第 1 轮）

| 编号 | 问题 | **结论** | 影响 |
| --- | --- | --- | --- |
| Q1 | `request` / `response` 放哪个模块？ | **整体迁到 `clara-model`**，core 的旧 `com.clara.model.api` 随迁移删除 | ⚠️ **已被 Q15 覆盖**：契约层（接口、枚举、流事件、DTO 基类）留 core，`com.clara.model.api` 不删除；实际迁入 model 的只有平台派生 DTO 与实现层（`request` / `response` / `status` / `http` / `codec` / `configuration`…）。依赖方向恒为 `model → core` |
| Q2 | 平台专属选项接口与两个 options 扩展点接口 | **删除**，平台差异由请求子类 + 派生 codec 承担 | codec 由「单类 + 钩子注入」改为「兼容模板 + 平台派生类」（§3.5）；`DeepSeekChatOptions` / `OpenAiImageOptions` / `VolcengineImageOptions` 逻辑搬进对应派生 codec。**落地情况**：两个 options 扩展点接口、三个 options 实现类与平台专属选项接口及其请求槽位、引用均已删除（批次 5/6） |
| Q3 | 能力基类是否抽象？ | **可实例化**，平台子类是可选强化 | 业务方（`clara-novel` 等）继续 `new ChatRequest(...)`，不受平台概念影响；只有 `ModelRequest` / `ModelResponse` 保留 `abstract` |
| Q4 | 是否顺带统一命名 | **类名与包名一起改**（`ImageGenerationResult` → `ImageResponse` 等） | 全仓 import 与类名批量调整，与批次 4 合并执行 |

### 11.2 已确认（第 2 轮）

| 编号 | 问题 | **结论** |
| --- | --- | --- |
| Q5 | 流式标志放哪 | **不放 DTO**：模型接口保留 `chat(request)` / `chat(request, observer)` 两个方法；兼容家族的 `stream` 字段只在编码阶段由 codec 写入请求体 |
| Q6 | 平台开关（`watermark`、`thinking`、`prefixCompletion`） | **配置给默认值 + 请求可覆盖**，字段单一可写入口 |
| Q7 | 配置类字段统一改名 | **类字段与 YAML 键名一起改**（`maxTextChars` → `maxPromptChars` 等），仓库内配置文件在同批次改齐。⚠️ **原「YAML 旧键由 resolver 兼容读取、不动用户配置文件」的写法已被 Q28 覆盖**：不做任何旧键兼容 |
| Q8 | Lombok 是否引入 Builder | **只用 `@Getter` + `@EqualsAndHashCode`**，保留手写构造器与校验；不引入 `@SuperBuilder` |
| Q9 | `StreamChunk` / `StreamDelta` 增量产物归哪 | **归 `codec`**（它是编解码输出，不是状态），命名为 `ChatStreamDelta` / `ImageStreamDelta` |
| Q10 | 平台 codec 与模型实现配对 | **模型通用 + 构造器注入 codec**（Q10-a）：`OpenAiCompatibleChatModel(config, codec, transport, credentials)` |
| Q11 | `ChatMessage` / `IContent` / `TokenUsage` / `Role` / `FinishReason` 归属 | **被 Q15 覆盖**：这些类型被能力接口签名引用，必须留在 `clara-core`（**实际分包**：`ChatMessage` / `TextContent` 在 `com.clara.kernel`，`IContent` / `MediaContent` 在 `com.clara.model.api`，`ToolCallContent` / `TokenUsage` 在 `com.clara.tool`，`Role` / `FinishReason` 在 `com.clara.enums`）；`model.message` 包**不再需要** |
| Q12 | 接口命名 | **一律以 `I` 开头**（`IChatModel`、`IHttpTransport`、`ICallSpec`…）；类、枚举、抽象类不加前缀。范围内 21 个现存接口：17 个改名、4 个删除（含 `EmbeddingModel`，见 Q29）；完整对照表见 [model-module-class-tree.md §0.1](model-module-class-tree.md)。仓库内其余约 62 个接口（`kernel` / `media` / `memory` / `mcp` / `orchestration` / `skill` / `application` / `novel`）**本次不改** |
| Q13 | 上下文的职责边界 | **拆成两层**（§2.4）：`ConversationContext`（tenantId / userId / **conversationId**，绑定当前 chat 会话，跨请求稳定）+ `CallContext`（**traceId** / deadline / cancellationToken，**每次请求新建，同会话内不同请求的 traceId 必须不同**）；`runId` / `instanceId` 属于工作流/技能维度，**移出上下文**由各自 DTO 携带。改动落在 batch 0 |
| Q14 | 枚举的模块归属 | **枚举一律定义在 `clara-core`**（Q14 的「枚举在 core」结论不变；**落点以工作区现状为准：`com.clara.enums`**，不是 `com.clara.model.api`），`clara-model` 内最终没有任何 `enum`。推论：`runtime` 的 `ModelModality` / `ModelExecutionMode` / `ModelMetadataSource` 移入 core；`CapabilityKind` 建在 core；`StreamEvent.Type` / `ImageGenerationEvent.Type` / 流增量 `Kind` 提升为**顶层枚举**后同样放 core |
| Q15 | 契约层与实现层的切分 | **契约在 `clara-core`、实现在 `clara-model`**：接口、枚举、流事件，以及**接口签名引用到的 DTO 基类**（`ChatRequest` / `ChatResponse` / `ImageRequest` …）留 core（**实际分包**：`com.clara.model.api` / `api.media` / `com.clara.kernel` / `com.clara.context` / `com.clara.enums` / `com.clara.tool`）；平台派生 DTO、`status`、`http`、`codec`、`configuration`、`provider`、`runtime`、`utils` 在 model。这**部分覆盖 Q1**（原结论「能力接口与 DTO 全部迁 model」不可行：接口引用 core 外的 DTO 会造成 core→model 循环依赖），也**覆盖 Q11**（`message` 包作废）。同时避免了包名 `com.clara.model.api` 跨两个模块的 split package |
| Q16 | 类型定义形式 | **禁止具名嵌套类 / 接口 / 枚举（含 `Builder`）**，一个类型一个文件：12 处嵌套类型全部摊平为顶层类型（含 `ProviderModelMetadata.Builder` → `ProviderModelMetadataBuilder`）；匿名类不在禁令内。清单见 [model-module-class-tree.md §0.1](model-module-class-tree.md) |

### 11.3 已确认（第 3 轮 · 错误提示与可观测性）

| 编号 | 问题 | **结论** |
| --- | --- | --- |
| Q17 | 中文错误提示的承载方式 | **`clara-core` 建错误消息表**：新增 `com.clara.kernel.exception.ErrorMessages`（`code → 中文模板` + 占位符插值），`ClaraError` 构造器的兜底消息由 `code` 改为 `ErrorMessages.of(code)`，`ExceptionUtils` 传入变量后插值。错误码本身不变（见 §13） |
| Q18 | 请求日志事件的接收通道 | **复用 `IStreamObserver`**，不新增独立监听接口：`StreamEventType` / `ImageGenerationEventType` 新增 `LOG`，`StreamEvent` / `ImageGenerationEvent` 新增 `log` 字段；接收方由 `CallContext` 的可空 `logObserver` 逐层传递，**不改任何现有 SPI 方法签名**（见 §14.4） |
| Q19 | 日志开关的位置与粒度 | **全局默认 + 平台覆盖 + 能力覆盖**：YAML 新增根节点 `observability.logLevel`，`transport.logLevel` 覆盖全局，`capabilities.<name>.parameters.logLevel` 覆盖平台；有效级别默认 `OFF`（见 §14.3） |
| Q20 | 是否引入日志框架 | **引入 SLF4J API，同时发事件**：根 pom 统一版本、`clara-model` 加 `slf4j-api` 依赖；日志实现（绑定）由宿主应用提供，主代码不引入任何绑定（见 §14.6） |
| Q21 | 日志内容与脱敏档位 | **两档：`BASIC`（元数据）/`VERBOSE`（含截断后的请求体、响应体与 SSE 逐事件）**；`Authorization` 等密钥类头在任何档位都不记录，端点一律去 query 与 userinfo（见 §14.5） |

### 11.4 已确认（第 4 轮 · 批次 0 开工前）

| 编号 | 问题 | **结论** |
| --- | --- | --- |
| Q22 | 子任务与父子上下文 | **删掉 `child(...)`，不引入 `spanId` / `parentTraceId`**：子任务需要不同的截止时间或跟踪标识时显式新建 `CallContext`。旧 `child()` 的「继承租户与用户、共享取消令牌、deadline 只能缩短」语义改为在调用点显式表达，不再隐藏在方法里（见 §2.4） |
| Q23 | `conversationId` 是否必填 | **必填**（构造期校验非空白，与 `tenantId` 同级）：没有真实对话的后台调用（媒体任务轮询等）用**任务 ID 之类稳定的语义值**自造会话 ID。不采用「可空」也不采用「自动生成默认会话」——前者会让日志失去会话维度，后者会引入隐式行为（见 §2.4） |

**Q1~Q23 确认完毕**（Q1~Q16 是重构主线，Q17~Q21 是错误中文化与可观测性，Q22~Q23 是上下文细则）。

### 11.5 已确认（第 5 轮 · YAML 注解化解析与改写纪律）

| 编号 | 问题 | **结论** |
| --- | --- | --- |
| Q24 | YAML 绑定注解加在哪里 | **注在运行时配置类上**：`@JsonCreator` + `@JsonProperty(required = true)` 标注在已有的全参构造器与字段上，绑定走 creator 因而**现有构造期校验全部保留**；不另建 YAML DTO、不自研反射框架（见 §7.4） |
| Q25 | 未知字段与旧键 | **未知项一律拒绝**（`FAIL_ON_UNKNOWN_PROPERTIES`）且**不提供任何旧键通道**：不使用 `@JsonAlias`，键名与配置字段一次性对齐，旧键在加载期直接报错（见 §7.4、§8.3） |
| Q26 | 注解化解析的时机 | **并入批次 3**：批次 3 本就要重建 `configuration.config` 继承体系并统一参数命名（`maxTextChars` → `maxPromptChars`），注解一次到位避免改两遍；批次 8 的 `observability` 节点也顺带支持（见 §10 批次 3） |
| Q27 | `JsonNodeUtils` 的处置 | **拆成两半**：配置向方法（`requiredObject` / `requiredText` / `optionalText` / `requiredInt` / `optionalBoolean` / `optionalUri` / `rejectUnknown` / `fieldSet`）随改造删除；协议向方法（`textOrNull` / `strictTextOrNull` / `getIntValue` / `optionalIntValue`）保留（编解码器在用），并随 utils 包上移到 `com.clara.utils`（Q31）；`safeHttpUri` 迁入 `SafeHttpUriDeserializer` |
| Q28 | 是否保留兼容代码 | **不保留**：本次按全新格式改写，禁止旧键别名、`@Deprecated` 过渡层、被取代的旧方法/旧类型、新旧并存的双入口、两套数据形状。宁可单批次改动面变大，也不引入过渡态；判定口径与例外（领域术语 `compatible`、入口签名不变）见 §8.3。**覆盖 Q7 里「旧键由 resolver 兼容读取」的写法** |

**至此 28 项设计决策全部确认。**

### 11.6 已确认（第 6 轮 · 去掉向量化能力）

| 编号 | 问题 | **结论** |
| --- | --- | --- |
| Q29 | 是否保留向量化（Embedding）能力 | **整体移除，不做这块功能支持**。删除清单：`com.clara.model.api.EmbeddingModel`（接口）、`EmbeddingRequest`、`EmbeddingResponse`、`ModelCapability.EMBEDDING`（14 项 → 13 项）、新设计的 `CapabilityKind.EMBEDDING`、`runtime.RegisteredModel` 的 `embeddingModel` 字段与 `getEmbeddingModel()`（构造器 4 参 → 3 参）、`ModelApiImmutabilityTest` 的向量用例。**config / request / response / codec / resolver 侧本来就没有任何向量化实现**（实测无实现类、无 `toXxxConfig`、YAML 里也没有 embedding 能力节点），因此这是纯删除 |
| Q29-a | 与语义记忆的关系 | **`clara-memory` 保持不动**：它的语义记忆不依赖模型层——`MemoryEntry.vector` / `SemanticMemoryQuery.vector` 由调用方传入，`embeddingModel` / `embeddingRevision` 只是**字符串标签**（写在 Milvus collection spec 与 manifest 里做版本隔离）。因此移除模型侧向量化不影响 clara-memory 的编译与测试；**「谁来产生向量」在本次范围内不解决**，需要时由宿主应用或后续独立功能提供 |
| Q29-b | 遗留文档里的 Embedding 章节 | **不动**：`docs/architecture/java8-ai-capability-migration.md`（首期能力范围、模型能力矩阵的 Embedding 列、Milvus 维度/版本冻结项）与 `docs/migration/tasks.md`（Alibaba Embedding 实测记录）**不做任何修改**，保留为历史记录；实施依据以本文档 §11.6 为准 |

**至此 29 项设计决策全部确认。**

### 11.7 已确认（第 7 轮 · 公共工具上移）

| 编号 | 问题 | **结论** |
| --- | --- | --- |
| Q30 | 工具类搬到 core 后放哪个包 | **`com.clara.utils`**（与 `com.clara.kernel` 平级的新顶层包，语义即「公共工具」）；`ExceptionUtils` 也放这里，与 `com.clara.kernel.exception.ErrorMessages` 分工清晰（工具 vs 领域值对象） |
| Q31 | `JsonNodeUtils` 依赖 Jackson 怎么办 | **`clara-core` 的 pom 加 `jackson-databind`**（版本用根 pom 统一的 `2.13.4`），`JsonNodeUtils` 随包一起上移。代价是最底层模块从此带 Jackson 并传播到全仓 classpath，收益是「公共工具真正可被任何模块引用」；`clara-model` / `clara-mcp` / `clara-application` 本来就用 Jackson，实际影响有限 |
| Q32 | 搬迁时机 | **作为独立小批（批次 U）提前做**：纯包搬迁 + 改 import、零逻辑改动，先把 `com.clara.utils` 建起来，后续批次直接用新包名，避免改两遍 |

**上移清单与两个必须处理的细节**：

| 类 | 依赖现状 | 搬迁处理 |
| --- | --- | --- |
| `MapUtils` / `NumberUtils` / `StringUtils` | 纯 JDK | 直接搬 |
| `ExceptionUtils` | `com.clara.kernel.*` + `com.clara.model.api.ModelProvider`（都已在 core） | 直接搬 |
| `FinishReasonUtils` | `FinishReason`（core）+ **`OpenAiCompatibleStreamAccumulator`（model 实现层，仅 Javadoc `{@link}`）** | 去掉该 import 与 `{@link}`，注释改为文字描述；顺带规范为 `final` + 私有构造器 |
| `JsonNodeUtils` | Jackson `JsonNode` | 依赖 core 新增 `jackson-databind`；按 Q27 配置向方法不再保留、只留协议向 4 个方法 |

**实测影响面**：使用方**全部在 `clara-model` 内部**（35 处 import，其它模块 0 处）——所以这是为「以后谁都能引用」做的架构调整，当前不改其它模块。

**至此 32 项设计决策全部确认。**

### 11.8 已确认（第 8 轮 · 注释完备性）

| 编号 | 问题 | **结论** |
| --- | --- | --- |
| Q33 | 重新生成的类要写到什么程度的注释 | **每个类型都要有中文 Javadoc，且类级必须写清「怎么用」**：① 类型级 = 是什么 / 在哪一层 / 谁创建谁调用 + **使用方法（实例从哪来 → 典型调用序列 → 是否需要 close）+ 生命周期与线程安全 + 失败语义（异常类型 / 错误码 / 可否重试）**，配置类还要给最小可用 YAML 片段；② 字段与枚举常量 = 语义 + 单位与取值范围 + 可空性 + 默认值来源，配置字段额外标 YAML 键名与必填性；③ 方法与构造器 = 首句「做什么」+ `@param`（含空值语义）/ `@return`（含空值语义、集合可变性）/ `@throws`（条件）。范围是**本次迁移产出的每个类型**（新建 / 改名 / 搬迁 / 重写），与代码同批次交付，未触碰的类不强制回补；「复述类名」的注释视为不合格。完整要求、判定标准与三个模板见 **§8.4** |

**至此 33 项设计决策全部确认。**

**已落地批次**：批次 U（6 个类上移 + core 加 `jackson-databind`）、批次 2（`http` 包上移改名，`ICredentialResolver` 改收 `ConversationContext`）、批次 3（config 继承树 + resolver + 注解绑定；YAML 改键 8 处 / 5 文件；loader 退化为门面；配置加载测试 6 项全绿）、批次 4-1（契约接口加 `I` 前缀、两个内部枚举提升为顶层枚举、删除向量化能力；Core 34 / Model 76 全绿）、批次 4-2a（`ModelRequest` / `ModelResponse` 抽象基类 + 能力请求/响应改名 + 三个枚举迁入 core）、批次 7（`ErrorMessages` + 兜底 + 全量测试），以及更早的批次 0（2026-09-20，67 个文件）、批次 1（`status` 包两个类）；**批次 5/6 的 chat 线与 image 线 codec 分层已落地**。
**仍未完成**：批次 6 剩余部分——视频 / 音频线的 codec 分层与嵌套类摊平；以及**批次 8**。
验证口径统一为 `mvn -o test -Dmaven.surefire.version=2.22.2 -Dtest='!DeepSeekChatLiveTest' -DfailIfNoTests=false`（`-Dmaven.surefire.version=2.22.2` 用于绕开离线环境缺失的 surefire-junit4:3.2.5）。

---

## 12. 本次改造不动的部分

- `com.clara.model.runtime` 的注册表 / 能力校验（`ModelRegistry`、`ModelCapabilityGuard` 等）；**例外**：`RegisteredModel` 因 Q29 去掉嵌入端点（字段、构造参数、getter）；`ModelModality` / `ModelExecutionMode` / `ModelMetadataSource` 三个枚举已随批次 4-2a 迁入 `com.clara.enums`。
- `com.clara.model.provider.media` 的媒体桥、multipart 构造、异步任务基类（`AbstractJsonMediaJobOperation`）；媒体桥接口**尚未改名**（目标名 `IProviderMediaAssetBridge`）。
- `utils` 下的工具类**已整体上移至 `clara-core` 的 `com.clara.utils`**（批次 U，Q30~Q32）：不再作为「本次不动」的部分；`ExceptionUtils` 只是换包，逻辑不变。
- YAML 的**字段语义**：字段集本身不扩张，但键名在批次 3 随注解绑定一次性对齐（`maxTextChars` → `maxPromptChars` 等，仓库内配置文件同步改，**不做旧键兼容**，见 §7.2 / §8.3）；批次 8 再新增 `observability` 根节点与 `transport.logLevel` 两个可选字段（见 §14.3）。
- `http` 往返骨架的**业务逻辑**（批次 2 只做改名与上移，逻辑零改动）。

---

## 13. 错误提示中文化（需求：每个异常都要有中文提示，不能只有一串 code）

### 13.1 现状问题

| 事实（**批次 7 改造前的现状；已全部修复**） | 位置（当前路径） |
| --- | --- |
| `ClaraError` 的 `safeMessage` 在缺省时**直接等于 `code`**（批次 7 后改为经 `ErrorMessages` 兜底） | [ClaraError.java:39](file:///e:/workspace_self/xxxx/clara/clara-core/src/main/java/com/clara/kernel/exception/ClaraError.java#L39) |
| `ExceptionUtils.fail(...)` 把 `code` 当消息传入（批次 7 后由 `ErrorMessages` 生成消息并插值） | [ExceptionUtils.java:199](file:///e:/workspace_self/xxxx/clara/clara-core/src/main/java/com/clara/utils/ExceptionUtils.java#L199)（批次 U 已上移至 `com.clara.utils`） |
| `RuntimeErrors.error(...)` 同样把 `code` 当消息（走 `ClaraError` 兜底即得中文，无需改动） | [RuntimeErrors.java:19](file:///e:/workspace_self/xxxx/clara/clara-model/src/main/java/com/clara/model/runtime/RuntimeErrors.java#L19) |
| 错误码是字符串常量（约 90 个），且每个常量**已有中文 Javadoc** | [ErrorCodes.java](file:///e:/workspace_self/xxxx/clara/clara-core/src/main/java/com/clara/kernel/exception/ErrorCodes.java) |

结果：上层（CLI / 前端 / 日志）只能拿到 `MODEL_PROVIDER_FAILED` 这样的串，用户看不懂；平台名、HTTP 状态码等关键上下文只存在于 `provider` / `providerCode` 字段，主消息里看不到。

### 13.2 方案：core 建一张「错误码 → 中文模板」表

```
com.clara.kernel.exception
└── [C] ErrorMessages    ★ code → 中文模板，支持 {provider} / {status} / {code} … 占位符插值
```

```java
public final class ErrorMessages {
    /** 无变量的中文提示；未登记的 code 原样返回 code。 */
    public static String of(String code);
    /** 带变量的中文提示；模板中的占位符按 args 替换，缺失的占位符原样保留。 */
    public static String of(String code, Map<String, String> args);
}
```

三个落点：

1. **`ClaraError` 构造器兜底改一行**：`safeMessage == null || safeMessage.equals(code) ? ErrorMessages.of(code) : safeMessage`。
   之所以连「显式把错误码当消息」也一并替换：仓库内约 25 个模块的错误工厂都是 `new ClaraError(code, category, code, …)` 这种写法（`WorkflowEngine` / `McpErrors` / `WorkspaceErrors` / `ConversationStoreErrors` / `MediaJobStateMachine` …），
   它们的意图是「不额外泄露信息」，而非「消息必须是裸错误码」。因此这一行让**全仓库所有模块**的异常一次性获得中文提示，无需逐个改抛点；
   真正传了自定义消息的调用点（如契约测试里的 `"denied"`）保持原样。
2. **`ExceptionUtils` 的关键场景传变量**：
   `providerFailure(provider, status)` → 「模型平台调用失败：平台 OPENAI，返回 429」；
   `credentialUnavailable(provider)` → 「模型凭据不可用：未解析到平台 OPENAI 的可用密钥，请检查凭据配置」；
   平台或平台码缺失时消息中显示为「未知」。变量只进消息，**不重复写入 `safeDetails`**——平台与平台码已有 `ClaraError.getProvider()` / `getProviderCode()` 两个专属字段，重复存放会让上层拿不准该读哪个。
3. **`RuntimeErrors` / `CancellationToken` / `DefaultMediaJobScheduler`** 等直接构造 `ClaraError` 的点，靠第 1 条的兜底自动获得中文，不需要改动。
4. **`MediaJobProviderException`**（`clara-core` 的 `media.job`，普通 `RuntimeException`，消息由调用方传入）：唯一生产点 `AbstractJsonMediaJobOperation.mapped(...)` 原先硬编码英文 `"provider media task failed"`，改为透传底层 `ClaraException.getError().getSafeMessage()`，中文因此贯穿到媒体任务链路。

### 13.3 消息措辞规范（三条硬规则）

1. **格式**：`发生了什么：关键上下文`，中文全角标点，**句尾不加句号**；不含英文错误码（错误码由 `code` 字段承载，消息里不重复）。
2. **内容**：必须自足——只读消息就能判断「哪个平台 / 哪一步 / 大概原因」；可选给一句处置建议（如「稍后重试」「请检查配置项 X」）。
3. **安全**：不得写入密钥、请求体、平台返回原文；平台侧信息只允许平台名与平台码（如 HTTP 状态码）。

### 13.4 占位符规范

| 占位符 | 含义 | 填充方 |
| --- | --- | --- |
| `{provider}` | 平台名（`OPENAI` / `ANTHROPIC` / `DEEPSEEK` / `VOLCENGINE` / `ALIBABA`） | `ExceptionUtils` 带 `ModelProvider` 的重载 |
| `{status}` | 平台返回的 HTTP 状态码 | `providerFailure` |
| `{providerCode}` | 平台自定义错误码 | `providerEventFailure` |
| `{modelId}` / `{capability}` | 模型标识 / 能力名 | `ModelRegistry` / `ModelCapabilityGuard` |
| `{limit}` / `{actual}` | 上限与实际值（体积、条数、轮询次数） | 资源限制类错误 |
| `{traceId}` | 端到端跟踪标识 | 上下文注入，默认**不写进消息**（只在 `safeDetails`），避免消息里出现无法解读的长串 |

插值实现约束：单遍扫描模板，不做递归替换（避免变量值里含 `{x}` 引发二次展开）；缺失变量保留 `{name}` 字面量，便于测试发现漏填。

### 13.5 覆盖范围与验证

- 覆盖 `ErrorCodes` 中已定义的全部 `INVOCATION_` / `CAPABILITY_` / `MODEL_` / `MEDIA_` 前缀错误码；其余模块前缀（`MCP_` / `WORKSPACE_` / `SKILL_` 等）**本次一并登记**（表是共享的，登记成本只是一行文案）。
- 未登记的 code 回退为 `code` 本身（不抛异常、不返回 null），保证新增错误码忘记登记时行为退化为现状而非报错。
- 单元测试：遍历 `ErrorCodes` 的全部常量，断言 `ErrorMessages.of(code)` 不等于 `code`（即已登记）；断言 `providerFailure` 的插值结果含平台名与状态码；断言未知 code 回退。

---

## 14. 请求过程日志与可观测性（需求：可开关的详细请求日志 + 事件可接收）

### 14.1 目标

一次模型调用（请求准备 → 发送 → 收到响应 → 解码 → 完成/失败/取消）产生**详细日志**；日志是否产生由配置决定；开启后调用方能在调用过程中**实时收到日志事件**（不是只能事后翻文件）。

### 14.2 事件模型（复用 `IStreamObserver`，Q18）

```
com.clara.kernel.exception（★ 批次 8，未开工）  ← 后台与应用侧共享的观测模型
├── [E] RequestLogLevel                  ★ OFF / BASIC / VERBOSE（**枚举落 `com.clara.enums`**，Q14）
├── [E] RequestLogStage                  ★ REQUEST_PREPARED / REQUEST_SENT / RESPONSE_RECEIVED /
│                                          STREAM_EVENT / RESPONSE_DECODED / RETRY_SCHEDULED /
│                                          CALL_FAILED / CALL_CANCELLED
├── [C] RequestLogEntry                  ★ 一条日志事件的结构化载荷
└── [C] RequestLogConfig                 ★ 生效级别 + 截断上限（effective level + maxBodyChars）

com.clara.enums（★ 批次 8，未开工）          ← Q14 的枚举落点
└── [E] RequestLogLevel / RequestLogStage（同上两个枚举的实际位置）

com.clara.model.api                       ← 契约层
├── [E] StreamEventType                  → 新增取值 LOG（非终态，可出现在流中任意位置；枚举实际在 `com.clara.enums`）
├── [C] StreamEvent                      → 新增字段 log: RequestLogEntry（仅 LOG 事件非 null）
├── [E] ImageGenerationEventType（`com.clara.enums`） → 新增取值 LOG
└── [C] ImageGenerationEvent（`com.clara.model.api.media`） → 新增字段 log: RequestLogEntry

clara-model
└── [C] RequestLogEmitter                ★ 按有效级别组装 RequestLogEntry，同时走 SLF4J 与观察者（落在 `com.clara.model.http`）
```

`RequestLogEntry` 字段（全部不可变，`@Getter`）：

| 字段 | 说明 |
| --- | --- |
| `stage` | 阶段（如上枚举） |
| `level` | 本条日志级别（`DEBUG` / `INFO` / `WARN` / `ERROR`，映射到 SLF4J 与事件） |
| `message` | **中文**人读文案（与 §13 同风格，复用 `ErrorMessages` 的措辞规则） |
| `provider` | 平台名，可为 null |
| `method` / `endpoint` | HTTP 方法与**脱敏后**的端点（见 §14.5） |
| `statusCode` | HTTP 状态码，未收到响应时为 null |
| `durationMillis` | 该阶段的耗时（`REQUEST_SENT` 记发送耗时，`RESPONSE_RECEIVED` 记往返耗时） |
| `requestBytes` / `responseBytes` | 请求体/响应体字节数 |
| `traceId` | 取自 `CallContext` |
| `details` | `Map<String,String>`，结构化补充（如 `attempt` / `truncated` / `contentType`），机器可读 |

> `LOG` 事件**不影响**终态语义：`RESPONSE_COMPLETE` / `ERROR` 仍是唯一终态，`LOG` 可在终态之后被忽略（下游按 `type` 过滤即可）。

### 14.3 开关配置（全局默认 + 平台覆盖 + 能力覆盖，Q19）

```yaml
observability:            # 新增根节点（可省略，省略即 OFF）
  logLevel: BASIC         # OFF | BASIC | VERBOSE
  maxBodyChars: 2048      # VERBOSE 档下请求体/响应体的截断上限

transport:
  # …既有字段
  logLevel: VERBOSE       # 可选：覆盖全局

capabilities:
  chat:
    # …既有字段
    parameters:
      logLevel: OFF       # 可选：覆盖平台（parameters 已是自由标量表，无需改白名单）
```

优先级：`capabilities.<name>.parameters.logLevel` ＞ `transport.logLevel` ＞ `observability.logLevel` ＞ `OFF`。

配套改动：
- `ProviderYamlConfigurationLoader`：`ROOT_FIELDS` 加 `observability`，`TRANSPORT_FIELDS` 加 `logLevel`；新增 `observability` 节点解析（未知字段仍拒绝）。
- `ProviderTransportConfiguration`：加字段 `logLevel`（可空）。
- `ProviderConfiguration`：加字段 `ObservabilityConfiguration`（全局默认），并提供 `effectiveLogLevel(capabilityName)`，把三级优先级收敛到一个方法里，避免每个适配器各自判断。
- 非法取值（如 `logLevel: DEBUG`）在加载期报错（`invalidConfig`），不静默降级。

### 14.4 日志事件的传递路径（不改任何 SPI 方法签名）

```
调用方（业务）
  └─ 构造 CallContext(traceId, deadline, cancellationToken, logObserver)   ← 新增可空字段
       └─ 构造 ChatRequest(conversation, call, …)
            └─ IChatModel.chat(request)
                 └─ ModelInvoker.exchangeStreaming(request, conversation, call, spec, listener)
                      ├─ RequestLogEmitter.emit(REQUEST_PREPARED / REQUEST_SENT)
                      ├─ IHttpTransport.execute(...)                 ← 签名不变
                      └─ RequestLogEmitter.emit(RESPONSE_RECEIVED / CALL_FAILED)
                           └─ logObserver.onEvent(new StreamEvent(LOG, seq, …, logEntry))  ← 复用 IStreamObserver
```

- **同步调用**：`logObserver` 由调用方在 `CallContext` 上设置；未设置时日志只走 SLF4J，不产生事件。
- **流式调用**：模型实现把方法参数的 `observer` 作为日志接收方（若 `CallContext.logObserver` 已设置则两者都发：业务事件发参数 `observer`，日志事件发 `logObserver`）。
- **为什么放 `CallContext` 而不是 `ModelRequest`**：`http` 层（`IHttpTransport` / `SseParser` / `ModelInvoker`）**不允许依赖 `request` 包**（§2 依赖方向），只有 `CallContext` 能被全链路拿到；若放 request，就必须给每个 SPI 方法加参数，改动面大且破坏「http 层与能力无关」的设计。
- **日志发射器归属**：`RequestLogEmitter` 由模型适配器/`ModelInvoker` 按「有效级别 + `CallContext`」创建，作为内部参数传给 `ModelInvoker` 的往返方法（内部私有方法，非对外 SPI）。**`RequestLogEmitter` 不放进 `clara-core`**：它是实现细节，包含 SLF4J 依赖。

### 14.5 两档内容与脱敏（Q21）

| 内容项 | `BASIC` | `VERBOSE` |
| --- | --- | --- |
| 阶段、方法、脱敏端点、平台、`traceId` | ✔ | ✔ |
| HTTP 状态码、往返耗时、请求/响应字节数 | ✔ | ✔ |
| 请求头 | 仅**头名**（值一律 `***`） | 同左（密钥类头连头名都不记，见下） |
| 请求体 / 响应体 | ✗ | ✔ 截断到 `maxBodyChars`（默认 2048），超出时 `details.truncated=true` 并注明原始字节数 |
| SSE 逐事件 | ✗ | ✔ 事件名 + data 长度 + 截断内容 |
| 错误码 + 中文消息 | ✔ | ✔ |

**硬约束（任何档位都成立）**：

1. `Authorization`、`api-key`、`x-api-key`、`apiKey`、`Cookie`、`Set-Cookie` 等**永不记录**（头名也不出现在日志里）；其余请求头只记头名，值统一 `***`；仅 `Content-Type` / `Accept` / `Content-Length` 记明文值。
2. 端点只保留 `scheme://host[:port]/path`，**query 与 userinfo 一律丢弃**（`?key=…` 类签名参数是最常见的泄露点）。
3. 截断在字节层完成后再解码为字符串，避免超长响应体在内存中完整驻留两次。
4. 与 §13 的分工：**异常消息**会外传给业务方，因此严格的「不含平台原文」；**日志事件**是受控的本地观测通道，`VERBOSE` 下允许携带截断后的平台原文，便于排障。

### 14.6 SLF4J 接入（Q20）

- 根 `pom.xml` 的 `dependencyManagement` 增加 `org.slf4j:slf4j-api`（选 Java 8 兼容线，`1.7.x`）；`clara-model` 增加该依赖。
- **不引入任何日志实现（绑定）到主代码**：绑定由宿主应用（如 `clara-application` / CLI 模块）决定；测试模块可加 `slf4j-simple`（`test` 作用域）以便本地观察。
- `RequestLogEmitter` 内部用 `LoggerFactory.getLogger(...)`，`OFF` 档下**不调用 logger**、不组装消息（先判 `isEnabled()` 再构造参数），保证关闭时零开销。
- 无绑定时的行为：SLF4J 打印一次 NOP 警告后静默，**不影响事件通道**（事件不依赖 SLF4J）。

### 14.7 验证

单元测试（新增）：
1. `logLevel=OFF` → 观察者收不到任何 `LOG` 事件，且 `RequestLogEmitter.isEnabled()==false`；
2. `BASIC` → 事件数正确、`message` 为中文、`details` 中**不含** body、请求头值全部为 `***`；
3. `VERBOSE` → body 出现且被截断为 `maxBodyChars`，`details.truncated=true`，SSE 逐事件可见；
4. 端点脱敏：带 query 与 userinfo 的 URI 在日志中只剩 `scheme://host/path`；
5. 优先级：能力 > 平台 > 全局 > OFF 四级逐一断言；
6. `logObserver` 为 null → 不抛异常，仅 SLF4J 路径；
7. 观察者抛异常 → 不影响主流程（沿用 `EventEmitter` 现有的「吞掉观察者异常」策略）。

回归：`DeepSeekChatLiveTest` 以 `VERBOSE` 跑一次，人工确认日志内容无密钥、无 query，中文可读。
