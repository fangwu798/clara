# clara-model 类结构文档（目标结构：类树 · 主要方法 · 职责）

> **2026-10 传输层补充**：`sse.SseParser` / `sse.SseState` / `sse.SseEvent` 与 `Jdk8HttpTransport` 中的
> `HttpURLConnection` 实现已下沉到独立模块 `clara-transport`（`DefaultSseEventParser` / `JdkHttpTransport`），
> 通用契约位于 `com.clara.core.transport`；`clara-model` 的 `Jdk8HttpTransport` 现在只是适配器。
> 下文涉及这些类的历史结构描述用于理解迁移前设计，当前结构以代码为准。

> 配套文档：[model-module-redesign.md](model-module-redesign.md)（设计原则、方案对比、决策记录与待确认问题）。
> 本文只画结构：包树、继承树、每个类的职责与主要方法。
>
> **已确认的结论**（八轮问答共 33 项决策，详见配套文档 §11）
> 1. 契约层**留 `clara-core`**（Q15，部分覆盖 Q1），但**按类型分落多个包**——以下包归属以工作区现状为准：
>    `com.clara.model.api`（能力接口、流契约接口、流事件、请求/响应基类：`IChatModel` / `IStreamingChatModel` / `IStreamObserver` / `IStreamHandle` / `IContent` / `StreamEvent` / `ModelRequest` / `ModelResponse` / `MediaContent`）、
>    `com.clara.model.api.media`（媒体能力接口与媒体请求/响应：`IImageGenerationModel` / `IStreamingImageGenerationModel` / `IImageGenerationObserver` / `IAudioTranscriptionModel` / `ISpeechSynthesisModel` / `IVideoGenerationModel` / `ImageRequest` / `ImageResponse` / `VideoRequest` / `VideoResponse` / `ImageGenerationEvent` / 音频请求响应 / `TranscriptionSegment` 等）、
>    `com.clara.kernel`（内容/请求/响应/参数 DTO：`ChatMessage` / `TextContent` / `ChatRequest` / `ChatResponse` / `ModelParameters`）、
>    `com.clara.context`（`ConversationContext` / `CallContext` / `CancellationToken`）、
>    **`com.clara.enums`（全部枚举）**；`request` / `response` / `status` / `http` / `codec` / `configuration` 等实现层建在 `clara-model`；
> 2. **删除** 平台专属选项接口与 `OpenAiCompatibleOptions` / `OpenAiCompatibleImageOptions`，平台差异由请求子类 + **派生 codec** 承担。**现状**：均已删除，槽位与引用已全部清理；
> 3. 能力基类（`ChatRequest` / `ImageRequest` / …）**可实例化**，只有 `ModelRequest` / `ModelResponse` 是 `abstract`；
> 4. 类名与包名**一起统一**（`ImageGenerationResult` → `ImageResponse` 等）；
> 5. 流式标志**不放 DTO**，模型接口保留同步 / 流式两个方法；
> 6. 平台开关：**配置给默认值 + 请求可覆盖**；
> 7. 配置类字段统一改名，**YAML 键名同批次改齐、仓库内配置文件同步更新，不做旧键兼容**（见规则四）；
> 8. Lombok **只用 `@Getter` + `@EqualsAndHashCode`**，不引入 Builder；
> 9. 增量产物（`ChatStreamDelta` / `ImageStreamDelta`）**归 `codec`**；
> 10. 平台 codec 与模型配对：**模型通用 + 构造器注入 codec**；
> 11. **被 Q15 覆盖**：`ChatMessage` / `IContent` / `TokenUsage` / `Role` / `FinishReason` 留 core，但**实际分落多个包**——`ChatMessage` / `TextContent` / `ModelParameters` 在 `com.clara.kernel`，`IContent` 在 `com.clara.model.api`，`TokenUsage` / `ToolCallContent` 在 `com.clara.tool`，`Role` / `FinishReason` 在 `com.clara.enums`；`model.message` 包作废；
> 12. **接口一律以 `I` 开头**（`IChatModel`、`IHttpTransport`、`ICallSpec`…），命名规范见 §0.1；
> 13. **上下文拆成「会话 + 请求」两层**：`ConversationContext`（tenantId / userId / conversationId，绑定当前 chat 会话）+ `CallContext`（traceId / deadline / cancellationToken，**每次请求新建、traceId 必不同**）；`runId` / `instanceId` 移出上下文（见配套文档 §2.4）。**✅ 批次 0 已落地**：`InvocationContext` 已删除，`child(...)` 不再提供（Q22），`conversationId` 必填、无会话时自造（Q23）；
> 14. **枚举一律定义在 `clara-core` 的 `com.clara.enums`**（Q14 的「枚举在 core」结论不变，**落点以工作区现状为准**，不是 `com.clara.model.api`），`clara-model` 内不出现任何 `enum`；**禁止具名嵌套类型（含 `Builder`）**，一个类型一个文件（Q16）——见 §0.1 规则二 / 规则三；
> 15. **每个异常都要有中文提示**：core 新增 `ErrorMessages` 错误码 → 中文模板表，`ClaraError` 兜底消息不再等于 `code`（Q17，详见配套文档 §13）。**✅ 批次 7 已落地**（102 个错误码全部登记）；
> 16. **请求过程日志可开关、可事件接收**：复用 `IStreamObserver` 发 `LOG` 事件，`CallContext` 携带可空 `logObserver`，开关按「能力 > 平台 > 全局 > OFF」生效（Q18~Q21，详见配套文档 §14）；
> 17. **YAML 配置改注解绑定**（Q24~Q27）：配置类用 `@JsonCreator` + `@JsonProperty(required = true)` 声明文件形状（绑定走构造器，**构造期校验保留**），未知项一律拒绝且**不提供旧键通道**；新增 `StrictYamlBinder` 与 3 个自定义反序列化器，`ProviderYamlConfigurationLoader` 退化为门面（见 §5.4，并入批次 3）；
> 18. **不留兼容代码**（Q28）：按全新格式改写，禁止旧键别名（`@JsonAlias`）、`@Deprecated` 过渡层、被取代的旧方法/旧类型、新旧并存的双入口与两套数据形状（见规则四）；
> 19. **不做向量化能力**（Q29）：删除 `EmbeddingModel` / `EmbeddingRequest` / `EmbeddingResponse` / `ModelCapability.EMBEDDING` / `CapabilityKind.EMBEDDING` 与 `RegisteredModel` 的嵌入端点（见规则一与 §0.2 的 B / J 组）。`clara-memory` 的语义记忆（向量由调用方传入）与遗留迁移文档**保持不动**（Q29-a / Q29-b）；
> 20. **公共工具上移到 core**（Q30~Q32）：原 `clara-model` 的 `com.clara.model.utils` 六个类整体搬到 **`clara-core` 的 `com.clara.utils`**（`core` 的 pom 随之加 `jackson-databind`），作为独立小批（批次 U）提前做、零逻辑改动（见 §0.1 规则零与 §8.2）；
> 21. **注释完备**（Q33）：本次迁移产出的每个类型都要有中文 Javadoc——类级必须写清「是什么 + 怎么用（创建 → 调用序列 → 收尾）+ 生命周期/线程安全 + 失败语义」，字段与枚举常量要标单位/范围/可空/默认值（配置类还要标 YAML 键），方法与构造器要有 `@param` / `@return` / `@throws`（见 §0.1 规则五）。
>
> **图例**
> - `★` 本次新增 ｜ `→` 改名或搬迁（职责不变） ｜ `=` 本次不动 ｜ `✗` 本次删除
> - 表格「主要方法」只列**对外/受保护方法**；Lombok 生成的 getter/setter 不逐条列出，用 `getXxx()` 概括。
> - **未改动类**的方法签名取自当前源码（已核实）；**新增类**的签名是设计提案。
> - 「原 `ProviderXxx`」指的是**现有代码里还没有 `I` 前缀的旧名**，仅作对照，不参与本次改名。
> - 实施顺序见配套文档 §10（主线 7 个批次 0~6，另有独立批次 7 错误中文化 / 8 可观测性）。**当前进度：批次 U、2、3、4-1、4-2a、7 ✅ 已落地（另有批次 0、1）；chat 线与图片线的 codec 分层（批次 5/6 的 chat / image 部分）✅ 已落地，平台专属选项接口及其槽位删除 ✅ 已落地；批次 6 的视频 / 音频线的 codec 分层、嵌套类摊平**未完成**；批次 8 未开工。**
>   - 批次 U：6 个工具类上移 `clara-core` 的 `com.clara.utils` + core 加 `jackson-databind`；
>   - 批次 2：`provider.http.*` 全删、`com.clara.model.http` 落地，`ICredentialResolver` 改收 `ConversationContext`；
>   - 批次 3：config 继承树 + resolver + 注解绑定落地，YAML 改键 8 处 / 5 文件，loader 退化为门面，配置加载测试 6 项全绿；
>   - 批次 4-1：契约接口加 `I` 前缀、两个内部枚举提升为顶层枚举、删除向量化能力（Core 34 / Model 76 全绿）；
>   - 批次 4-2a：`ModelRequest` / `ModelResponse` 抽象基类落地，能力请求/响应改名（`ImageRequest` / `ImageResponse` / `TranscriptionResponse` / `SpeechResponse`），`ModelModality` / `ModelExecutionMode` / `ModelMetadataSource` 迁入 core；
>   - 批次 5/6（chat + image）：codec 分层（兼容模板 + 平台派生 + 读流器 + 状态类）落地，4 个对话模型与图片模型改为构造器注入 codec，入口调用一次 `codec.adapt(request)`；平台专属选项接口及其槽位已删除。

---

## 0. 包级结构总览

### 0.1 命名与定义规范（六条硬规则）

**规则零：契约在 `clara-core`，实现在 `clara-model`（Q15）。** 这条决定了下面规则的落点：

| 层 | 模块 · 包（**以工作区现状为准**） | 内容 |
| --- | --- | --- |
| **契约层（模型 API）** | `clara-core` · `com.clara.model.api` | 能力接口、流契约接口、流事件、请求/响应基类（`IChatModel` / `IStreamingChatModel` / `IStreamObserver` / `IStreamHandle` / `IContent` / `StreamEvent` / `ModelRequest` / `ModelResponse` / `MediaContent`） |
| **契约层（媒体 API）** | `clara-core` · `com.clara.model.api.media` | 媒体能力接口与媒体请求/响应（`IImageGenerationModel` / `IStreamingImageGenerationModel` / `IImageGenerationObserver` / `IAudioTranscriptionModel` / `ISpeechSynthesisModel` / `IVideoGenerationModel` / `ImageRequest` / `ImageResponse` / `VideoRequest` / `VideoResponse` / `ImageGenerationEvent` / 音频请求与响应 / `TranscriptionSegment` / `MediaRequestValues`） |
| **内核 DTO** | `clara-core` · `com.clara.kernel` | 内容/请求/响应/参数 DTO（`ChatMessage` / `TextContent` / `ChatRequest` / `ChatResponse` / `ModelParameters`）；异常值对象在 `com.clara.kernel.exception`（`ClaraError` / `ErrorCategory` / `ErrorCodes` / `ErrorMessages`） |
| **枚举** | `clara-core` · **`com.clara.enums`**（Q14 的落点，非 `com.clara.model.api`） | `ModelProvider` / `ModelCapability` / `CapabilityKind` / `Role` / `FinishReason` / `ModelModality` / `ModelExecutionMode` / `ModelMetadataSource` / `StreamEventType` / `ImageGenerationEventType` / `ImageStreamDeltaKind`；`ClaraException` 也在本包（**归属待确认**，见 §0.2 的 A 组） |
| **上下文** | `clara-core` · `com.clara.context` | `ConversationContext` / `CallContext` / `CancellationToken` |
| **公共工具** | `clara-core` · **`com.clara.utils`**（Q30~Q32） | `ExceptionUtils` / `FinishReasonUtils` / `JsonNodeUtils` / `MapUtils` / `NumberUtils` / `StringUtils` —— 任何模块都可能用到，因此不能放在 `clara-model` |
| **实现层** | `clara-model` | 平台派生 DTO（`request.*` / `response.*`）、`status`、`http`、`codec`、`configuration`、`provider`、`runtime`（`utils` 已上移） |

为什么契约类型也必须留 core：`IChatModel.chat(ChatRequest)` 的形参类型若在 `clara-model`，则 `clara-core` 要依赖 `clara-model`，而 `clara-model` 已依赖 `clara-core` → 循环依赖，Maven 直接构建失败。
因此契约层 = 「接口 + 它签名里用到的一切类型」，**它们分散在 core 的多个包**（`com.clara.model.api` / `api.media` / `com.clara.kernel` / `com.clara.enums` / `com.clara.tool` / `com.clara.context`），但都不出 core。**Q11 的 `message` 包据此作废**（`ChatMessage` / `IContent` 等留在 core）。

**规则一：接口一律 `I` 开头。** 类、抽象类、枚举不加前缀。本次改造范围内共 21 个现存接口，其中 **4 个删除**、**17 个改名**：

| 现状名 | 目标名 | 目标包 | 备注 |
| --- | --- | --- | --- |
| `ChatModel` | `IChatModel` | `com.clara.model.api`（core） | 对话同步入口 ✅ 批次 4-1 |
| `StreamingChatModel` | `IStreamingChatModel` | `com.clara.model.api`（core） | 对话流式入口 ✅ 批次 4-1 |
| `EmbeddingModel` | ✗ **删除** | — | Q29 结论：**不做向量化能力**，连同 `EmbeddingRequest` / `EmbeddingResponse` / `ModelCapability.EMBEDDING` / `RegisteredModel.getEmbeddingModel()` 一并移除 ✅ 批次 4-1 |
| `ImageGenerationModel` | `IImageGenerationModel` | **`com.clara.model.api.media`**（core） | 生图同步 ✅ 批次 4-1 |
| `StreamingImageGenerationModel` | `IStreamingImageGenerationModel` | **`com.clara.model.api.media`**（core） | 生图流式 ✅ 批次 4-1 |
| `AudioTranscriptionModel` | `IAudioTranscriptionModel` | **`com.clara.model.api.media`**（core） | 语音识别 ✅ 批次 4-1 |
| `SpeechSynthesisModel` | `ISpeechSynthesisModel` | **`com.clara.model.api.media`**（core） | 语音合成 ✅ 批次 4-1 |
| `VideoGenerationModel` | `IVideoGenerationModel` | **`com.clara.model.api.media`**（core） | 视频异步任务 ✅ 批次 4-1 |
| `StreamObserver` | `IStreamObserver` | `com.clara.model.api`（core） | 对话流回调 ✅ 批次 4-1 |
| `ImageGenerationObserver` | `IImageGenerationObserver` | **`com.clara.model.api.media`**（core） | 生图流回调 ✅ 批次 4-1 |
| `StreamHandle` | `IStreamHandle` | `com.clara.model.api`（core） | 流句柄（实现类 `status.StreamHandleState` 在 model） ✅ 批次 4-1 |
| `Content` | `IContent` | `com.clara.model.api`（core） | 内容基接口（`TextContent` 不加前缀，位于 `com.clara.kernel`） ✅ 批次 4-1 |
| 平台专属选项接口 | ✗ **删除** | — | Q2 结论，由请求子类取代（类型文件、`providerOptions` 槽位与引用均已清理） |
| `ProviderHttpTransport` | `IHttpTransport` | `com.clara.model.http`（model） | HTTP 传输 SPI ✅ 批次 2 |
| `ProviderRequestPlan` | `ICallSpec` | `com.clara.model.http`（model） | 请求侧描述 ✅ 批次 2 |
| `ProviderExchange` | `ICallExchange`（extends `ICallSpec`） | `com.clara.model.http`（model） | 请求侧 + 响应解析 ✅ 批次 2 |
| `ProviderCredentialResolver` | `ICredentialResolver` | `com.clara.model.http`（model） | 凭据解析 ✅ 批次 2 |
| `ProviderSseListener` | `ISseListener` | `com.clara.model.http.sse`（model） | SSE 事件回调 ✅ 批次 2 |
| `ProviderMediaAssetBridge` | `IProviderMediaAssetBridge` | `com.clara.model.provider.media`（model） | 媒体资产桥 —— **尚未改名**，现类名仍为 `ProviderMediaAssetBridge`（随视频 / 音频线批次一并改） |
| `OpenAiCompatibleOptions` | ✗ **删除** | — | Q2 结论，逻辑并入派生 codec ✅ 批次 5/6（chat） |
| `OpenAiCompatibleImageOptions` | ✗ **删除** | — | 同上 ✅ 批次 5/6（image） |
| —（新增） | `IModelConfigResolver` | `com.clara.model.configuration.resolver`（model） | 配置解析契约 ✅ 批次 3 |

> `http` / `codec` / `provider.media` / `configuration.resolver` 里的接口是**实现层 SPI**（只被 `clara-model` 内部实现与使用），
> 不属于模型 API 契约，因此跟实现一起留在 `clara-model`。
>
> **批次 2 ✅ 已落地**：上表 `ProviderHttpTransport` / `ProviderRequestPlan` / `ProviderExchange` / `ProviderCredentialResolver` / `ProviderSseListener` 五个旧接口名与旧包 `com.clara.model.provider.http` 已全部删除，对应的 `IHttpTransport` / `ICallSpec` / `ICallExchange` / `ICredentialResolver` / `ISseListener` 已落在 `com.clara.model.http`（左列仅作对照留存）；执行器 `ProviderInvoker` 已改名为 `http.ModelInvoker`，`ProviderInvoker.RawPlan` 已摊平为包私有的 `RawCallSpec`。
>
> **批次 4-1 ✅ 已落地**：`ChatModel` / `StreamingChatModel` / `ImageGenerationModel` / `StreamingImageGenerationModel` / `AudioTranscriptionModel` / `SpeechSynthesisModel` / `VideoGenerationModel` / `StreamObserver` / `ImageGenerationObserver` / `StreamHandle` / `Content` 共 11 个旧接口名与 `EmbeddingModel` 已全部删除，**旧名已全部删除、全仓 0 残留**（左列仅作对照留存，供迁移溯源）；新的 `I*` 接口已按「目标包」一列落位（媒体类接口在 `com.clara.model.api.media`）。
> 上表唯一**尚未落地**的是 `ProviderMediaAssetBridge` → `IProviderMediaAssetBridge`（现类名未改）。

**范围说明**：上表只覆盖本次改造涉及的接口（`clara-model` 全部 + `clara-core` 的 `com.clara.model.api`）。
仓库里另有约 62 个接口在范围之外（`com.clara.kernel`、`com.clara.media`、`com.clara.media.job`、`clara-memory`、`clara-mcp`、`clara-orchestration`、`clara-skill`、`clara-application`、`clara-novel`），本次**不改名**。
其中有两个是 `clara-model` 会直接实现/使用的：`MediaJobProviderOperation`（`AbstractJsonMediaJobOperation` 实现它）、`MediaInput`。若要求全仓统一，需要单独一轮改造。

**规则二：枚举一律定义在 `clara-core` 的 `com.clara.enums`（Q14 的「枚举在 core」结论不变，落点以工作区现状为准）。** 不得定义在 `clara-model`，也不得作为嵌套类型。
推论：`clara-model` 内最终**没有任何 `enum`**；`Role` / `FinishReason` 留 core（与规则零一致）。
**迁移要求**：枚举必须**直接新建为顶层枚举文件**放到位，不允许「先写成内部枚举、以后再摊平」的中间态；下表现行内部枚举在迁移时即产出顶层文件。

| 枚举 | 现状位置 | 目标位置（**已落地**） |
| --- | --- | --- |
| `ModelProvider` / `ModelCapability` / `Role` / `FinishReason` | core 顶层（原 `com.clara.model.api`） | **`com.clara.enums`**（`=` 顶层，仅换包） |
| `ModelModality` / `ModelExecutionMode` / `ModelMetadataSource` | clara-model `runtime`（顶层） | **移入** `com.clara.enums` ✅ 批次 4-2a |
| `CapabilityKind` | 原计划放 `configuration.resolver` | **改放** `com.clara.enums` |
| `StreamEvent.Type` | `StreamEvent` **内部枚举** | 提升为顶层 `StreamEventType`，放 `com.clara.enums` ✅ 批次 4-1 |
| `ImageGenerationEvent.Type` | `ImageGenerationEvent` **内部枚举** | 提升为顶层 `ImageGenerationEventType`，放 `com.clara.enums` ✅ 批次 4-1 |
| 流增量 `Kind` | clara-model `OpenAiCompatibleImageStreamAccumulator` **内部枚举** | 提升为顶层 `ImageStreamDeltaKind`，放 `com.clara.enums` ✅ 批次 5/6（image） |

> `com.clara.enums` 内另有 **`ClaraException`**（承载 `ClaraError` 的运行时异常）——它是类而非枚举，**位于 `com.clara.enums`（归属待确认）**：从语义上看它更像 `com.clara.kernel.exception`（与 `ClaraError` / `ErrorCodes` 同族）的成员，本文档按现状记录，是否调整待确认。

**规则三：禁止具名嵌套类 / 接口 / 枚举（含 `Builder`），一个类型一个文件（Q16）。** 具名嵌套类型一律摊平为顶层类型；**迁移时遇到即摊平，不留中间态**：

| 现状（嵌套） | 摊平后（顶层） | 目标包 |
| --- | --- | --- |
| `AnthropicStreamingChatModel.AsyncHandle` | ✗ **直接删除**：与批次 1 已建的 `status.StreamHandleState` 完全重复 | — |
| `AnthropicStreamingChatModel.Emitter` + `compatible.handler.EventEmitter` | 合并为 `ChatStreamEventEmitter` | `provider.compatible.handler`（**未开工**） |
| `OpenAiCompatibleStreamingImageModel.ImageEventEmitter` | `ImageGenerationEventEmitter` | `provider.compatible.handler`（**未开工**） |
| `ProviderInvoker.RawPlan` | `RawCallSpec`（包私有）✅ 批次 2 | `http` |
| `OpenAiCompatibleImageCodec.Output` | `ImageCodecOutput` ✅ 批次 5/6（image） | `codec.compatible` |
| `OpenAiCompatibleImageCodec.Decoded` | `ImageCodecPayload` ✅ 批次 5/6（image） | `codec.compatible` |
| `AnthropicStreamAccumulator.Block` | `AnthropicContentBlockState` ✅ 批次 5/6（chat） | `status` |
| `OpenAiCompatibleImageStreamAccumulator.Chunk` | `ImageStreamDelta`（设计已定）✅ 批次 5/6（image） | `codec.compatible` |
| `OpenAiCompatibleImageStreamAccumulator.Kind` | `ImageStreamDeltaKind`（顶层枚举，进 core）✅ 批次 5/6（image） | **`com.clara.enums`** |
| `VolcengineVideoJobFactory.Operation` | `VolcengineVideoJobOperation` | `provider.volcengine`（**未开工**） |
| `AlibabaNativeMediaJobFactory.Operation` | `AlibabaNativeMediaJobOperation` | `provider.alibaba`（**未开工**） |
| `ProviderModelMetadata.Builder` | `ProviderModelMetadataBuilder` | `runtime`（**未开工**） |

> 匿名类（如任务工厂里一次性实现回调的场合）不在此禁令内——它没有名字，不属于「具名嵌套类型」；
> 但能写成独立顶层类或 lambda 的，优先改写。

**规则四：不留兼容代码（Q28）。** 本次是**按全新格式改写**，不是渐进式升级：

| 禁止 | 含义 |
| --- | --- |
| 旧键 | YAML 键名与配置字段一次性对齐，**禁止 `@JsonAlias` 等别名机制**；旧键在加载期直接报「未知配置项」 |
| 旧方法 | 被取代的方法（如 8 个 `toXxxConfig`）**直接删除**，不标 `@Deprecated` 过渡，调用点在同批次内一次改完 |
| 旧类型 | 被拆/被改名的类型（`InvocationContext`、三个 options 实现类、`AsyncStreamHandle`、`ToolState`…）**直接删除**，不留同名空壳、不留 re-export、不留 `// removed` 注释 |
| 双入口 | 不允许新旧两套入口并存（新旧 loader / 配置类 / DTO 同时可用，或按开关走旧分支） |
| 旧命名 | 类名、包名、方法名按规则一与配套文档 §8.2 一次改到位，不做「新名 + 旧名一起用」 |

**落地方式**：宁可让单批次的改动面变大，也不引入过渡态。判定口径（自查三问）：① 有没有「为了接受旧输入而保留的分支/别名/重载/默认值兜底」？② 有没有仅为过渡而存在的 `@Deprecated` 类型或方法？③ 有没有两套并存的入口或两种数据形状？——任一为是即删。

**明确不属于兼容代码**：① 领域术语 `compatible`（`request.compatible` / `codec.compatible` / `provider.compatible`）指 **OpenAI 兼容协议家族**，与过渡兼容无关；② 保留对外入口的**签名**不变（如 `ProviderYamlConfigurationLoader.load(Path)`）属减少无谓改动，但入口内部只能有一条实现路径；③ `clara-core` 的 `com.clara.model.api` **包名**不变（Q15），包内类型仍按新格式改建。

**规则五：每个类型与每个公开成员都要有中文 Javadoc，类级说明必须写清「怎么用」（Q33）。**

适用范围：**本次迁移产出（新建 / 改名 / 搬迁 / 重写）的每一个类型**，core 与 model 两侧一视同仁。未触碰的类不强制回补（同规则四的口径）。

| 位置 | 必须写清 | 不合格表现 |
| --- | --- | --- |
| **类型**（class / abstract class / interface / enum） | ① 这个类型**是什么**、在体系中的位置（属于哪一层、谁实现、谁调用）；② **使用方法**：创建方式 → 典型调用序列 → 收尾/释放；③ 生命周期与线程安全性；④ 失败时的行为（抛什么、可否重试） | 「XXX 的配置类」「工具类」这类复述类名的空话 |
| **字段 / 枚举常量** | 语义 + **单位与取值范围** + **是否可空** + **默认值及其来源**；配置类字段还要标出**对应的 YAML 键名与必填性** | 只写「宽度」，不写单位、默认值、可空性 |
| **方法 / 构造器** | 一句话说明做什么 + 前置条件 + 副作用；`@param`（含**空值语义**）、`@return`（含**空值语义**、是否可变集合）、`@throws`（**什么条件下**抛、抛什么） | `@param x 参数` 之类的占位注释 |
| **接口 / 抽象类** | 接口写「实现方需保证的语义 + 调用方按什么顺序用」；抽象类写「子类必须实现什么、允许覆写什么、覆写时的约束」 | 只列方法名不写契约 |

**类级「使用方法」四要素**（缺一即视为不合格）：① 谁创建、从哪里获得实例；② 典型用法（步骤编号或行内代码片段）；③ 生命周期 / 线程安全 / 是否需要 `close`；④ 异常与失败语义（对应错误码或异常类型）。

**其余约定**：注释语言为中文，类型名 / 方法名 / YAML 键名 / 协议字段名保留英文原文；配置类与 YAML DTO 的类级注释需给出**最小可用 YAML 片段**；重写触碰到的旧类，注释**一并按新格式重写**，不复用与旧实现绑定的描述、不留 `@deprecated` 段；不写 `TODO` 占位、不保留注释掉的代码、不写仅为「以后可能用到」的说明。

**Javadoc 模板（逐个类型套用，此处各举一例）**：

```java
/**
 * OpenAI 兼容协议的对话编解码器。
 *
 * <p>用途：在 {@code provider.compatible} 的模型实现与平台 HTTP 协议之间做双向转换。
 * 本类是无状态的纯转换器，可被多线程共享；一次请求内重复调用 {@link #encode} 结果一致。
 *
 * <p>使用方法：
 * <ol>
 *   <li>由具体模型实现的构造器注入（不自行 new，便于平台子类替换）；</li>
 *   <li>发送前调用 {@link #encode(ChatRequest)} 得到请求体；</li>
 *   <li>流式场景把 SSE 每帧交给 {@link #decodeStreamChunk(String)}，非流式直接把响应体交给 {@link #decode(ChatResponse)}；</li>
 *   <li>无资源需要释放，无需 close。</li>
 * </ol>
 *
 * <p>失败语义：协议字段缺失或类型不符时抛 {@link ClaraException}，错误码 {@code MODEL_PROTOCOL_...}，
 * 分类 {@link ErrorCategory#PROTOCOL}，不可重试（重试由调用方按分类判定）。
 */
public final class OpenAiCompatibleChatCodec { ... }
```

```java
/**
 * 火山引擎视频生成平台配置。
 *
 * <p>用途：{@code volcengine.yml} 的 {@code video} 能力节点解析结果，由
 * {@code VolcengineConfigResolver} 从 {@link ProviderConfiguration} 派生，作为模型实现的构造参数。
 *
 * <p>使用方法：由 resolver 产出，调用方只读；生命周期随所属 {@code ProviderConfiguration}，
 * 本类自身不持有连接，无需释放。所有字段不可变，实例可跨线程共享。
 *
 * <p>最小可用 YAML：
 * <pre>
 * video:
 *   endpoint: https://...
 *   model: ...
 *   maxOutputs: 4      # 单次最多产出视频数，默认 1
 * </pre>
 */
public final class VolcengineVideoConfig {
    /** 生成接口地址，必填；仅允许 http/https，缺失或非法在构造期报错。 */
    private final URI endpoint;
    /** 单次请求最多产出的视频数，可选，默认 {@code 1}，取值 ≥ 1。对应 YAML 键 {@code maxOutputs}。 */
    private final int maxOutputs;
}
```

```java
/**
 * 模型调用结束的原因。
 *
 * <p>使用方法：由 codec 从平台响应映射得到（{@code com.clara.utils.FinishReasonUtils.of(String)}），
 * 随响应或流结束事件返回给调用方；调用方据此决定是否续跑（如 {@link #LENGTH}）或告警（{@link #ERROR}）。
 * 枚举不可变且线程安全，可直接比较引用。
 */
public enum FinishReason {
    /** 平台正常结束。 */
    STOP,
    /** 因达到最大 token / 输出数量上限而截断，调用方可选择续跑。 */
    LENGTH,
    /** 平台返回了无法识别的结束原因，由上层决定是否告警；不表示调用失败。 */
    ERROR
}
```

### 0.2 全量类型树（目标结构 · 逐类型标注主要功能）

标记：`[I]` 接口 ｜ `[C]` 类 ｜ `[A]` 抽象类 ｜ `[E]` 枚举 ｜ `[N]` 包私有顶层类型（无嵌套类型）
变化：`★` 新增 ｜ `→` 搬迁或改名 ｜ `=` 本次不改 ｜ `✗` 删除

#### A. `clara-core` 内核类型（跨模块共享，**不随本次迁移**；包归属以工作区现状为准）

```
com.clara.context（★ Q13：批次 0 拆出的两层上下文）
├── [C] ConversationContext              ★ Q13/Q23 会话绑定：tenantId（必填）/ userId / conversationId（必填），跨请求稳定
├── [C] CallContext                      ★ Q13 本次调用：traceId（必填）/ deadlineEpochMillis / cancellationToken（必填）；
│                                          logObserver（可空，Q18 日志事件接收方，**批次 8 才加**）；remainingMillis(now)
├── [C] CancellationToken                 =  协作式取消令牌
└── [C] InvocationContext                ✗ 已删除（批次 0 ✅），拆为上面两个，且**不提供 `child(...)`**（Q22）

com.clara.kernel（内容 / 请求 / 响应 / 参数 DTO）
├── [C] ChatMessage                      →  一条消息：角色 + 内容列表 + 名称 + 元数据（Q15 留 core，`model.message` 包作废）
├── [C] TextContent                      →  纯文本内容：getType() = "text" + getText()
├── [C] ChatRequest                      →  对话请求基类：messages / parameters / tools / clearTools（继承 `api.ModelRequest`，可实例化）
├── [C] ChatResponse                     →  对话响应基类：message / tokenUsage / finishReason（继承 `api.ModelResponse`）
└── [C] ModelParameters                  →  采样参数与覆盖：model / temperature / topP / topK / maxTokens / penalty / responseFormat / stops

com.clara.kernel.exception（异常与错误码）
├── [C] ClaraError                       =  领域错误值对象：错误码、类别、可否重试、平台归因、安全明细；
│                                          ★ 兜底消息改由 ErrorMessages 生成（Q17）
├── [E] ErrorCategory                     =  错误类别：鉴权 / 授权 / 限流 / 超时 / 资源限制 / 能力 / 配置 / 取消 / 外部服务
├── [C] ErrorCodes                        =  错误码常量表（常量已带中文 Javadoc，作为文案来源）
└── [C] ErrorMessages                    ★ Q17 错误码 → 中文提示模板 + 占位符插值；未登记 code 回退为 code

com.clara.enums（Q14：core 的枚举集中在此，**不在 `com.clara.model.api`**）
├── [E] ModelProvider / ModelCapability / CapabilityKind / Role / FinishReason   =  平台 / 能力 / 能力种类 / 角色 / 结束原因
├── [E] ModelModality / ModelExecutionMode / ModelMetadataSource  ←  批次 4-2a 从 clara-model 的 `runtime` 移入
├── [E] StreamEventType / ImageGenerationEventType                ★  批次 4-1 由内部枚举提升为顶层枚举
├── [E] ImageStreamDeltaKind                                     ★  批次 5/6（image）由内部枚举提升为顶层枚举
└── [C] ClaraException                   =  承载 ClaraError 的运行时异常（**位于 `com.clara.enums`（归属待确认）**，见 §0.1 规则二）

com.clara.utils（★ Q30~Q32：原 `clara-model` 的 `com.clara.model.utils` 整体上移，公共工具）
├── [C] ExceptionUtils                   →  错误工厂：按错误码 + 平台上下文构造 ClaraException（中文消息）
├── [C] FinishReasonUtils                →  结束原因文本 → FinishReason 的纯映射；★ 去掉对 model 累加器的 Javadoc 引用
├── [C] JsonNodeUtils                    →  历史 JSON 节点工具：★ 配置向方法删除（Q27），只留协议向 4 个方法
├── [C] MapUtils                         →  标量 Map 的不可变包装与读取
├── [C] NumberUtils                      →  数字解析与边界处理
└── [C] StringUtils                      →  字符串校验、UTF-8 解码、分隔拼接

com.clara.kernel / com.clara.enums（★ 批次 8 观测模型，**未开工**）
├── [E] RequestLogLevel / RequestLogStage ★ Q19 日志档位（OFF / BASIC / VERBOSE）与阶段（枚举落 `com.clara.enums`）
├── [C] RequestLogEntry                  ★ Q18/Q21 一条日志事件的结构化载荷：阶段、级别、中文文案、脱敏端点、
│                                          状态码、耗时、字节数、traceId、details
└── [C] RequestLogConfig                 ★ Q19 生效级别 + 截断上限（由「能力 > 平台 > 全局 > OFF」收敛得到）

com.clara.media / com.clara.media.job / com.clara.tool（外部依赖，本次不改，命名也不加 I 前缀）
├── [E] MediaType                        =  媒体类型：IMAGE / AUDIO / VIDEO / DOCUMENT
├── [C] MediaUpload / MediaInput / MediaMetadata / MediaAsset        =  媒体载体与元数据
├── [I] MediaStore / MediaValidator / MediaMetadataProbe             =  媒体存储、校验与探测端口
├── [I] MediaJobProviderOperation / MediaJobScheduler / MediaJobStore =  异步媒体任务的提供商操作、调度与存储
├── [C] MediaJob / MediaProviderJobSnapshot / MediaProviderJobStatus =  任务状态与提供商侧快照
└── [C] ToolSpecification / TokenUsage / ToolCallContent             =  工具声明（名称、描述、JSON Schema）、token 用量、工具调用内容
```

#### B. `clara-core` 契约层与内核类型（**实际分包**：接口在 `com.clara.model.api` / `api.media`，DTO 在 `com.clara.kernel`，枚举在 `com.clara.enums`）

```
com.clara.model.api（能力接口 + 流契约 + 流事件 + 请求/响应基类）
├── 【能力接口】
│   ├── [I] IChatModel                     →  同步对话入口：chat(ChatRequest) → ChatResponse
│   └── [I] IStreamingChatModel            →  流式对话入口：chat(request, observer) → IStreamHandle
├── 【流契约】
│   ├── [I] IStreamObserver                →  对话流事件回调：onEvent(StreamEvent)
│   ├── [I] IStreamHandle                  →  流式调用句柄：cancel() / isDone()
│   └── [I] IContent                       →  内容基接口：getType()
├── 【流事件】
│   └── [C] StreamEvent                    =  一次对话流事件：类型（`StreamEventType`）、序号、负载、工具调用信息、终态响应或错误；
│                                              ★ 批次 8 计划新增字段 log: RequestLogEntry（仅 LOG 事件非 null，Q18，未开工）
├── 【请求 / 响应基类】（平台派生类在 clara-model 的 request / response 包）
│   ├── [A] ModelRequest                   ★ 根基类：conversation（会话）+ call（请求）——**无 modelId / provider 槽位**（模型 ID 由 `ModelParameters.model` 承载，平台由配置与请求子类类型决定）
│   └── [A] ModelResponse                  ★ 根基类：providerMetadata（平台、协议档位、平台原始标识）
├── 【内容】
│   └── [C] MediaContent                   →  媒体内容：媒体类型 + 资产 ID
└── [I] 平台专属选项接口                    ✗ 删除（Q2）：平台差异改由请求子类承担（类型文件、`providerOptions` 槽位与引用均已清理）

com.clara.model.api.media（媒体能力接口 + 媒体请求/响应）
├── 【能力接口】
│   ├── [I] IImageGenerationModel          →  同步生图：generate(ImageRequest) → ImageResponse
│   ├── [I] IStreamingImageGenerationModel →  流式生图：generate(request, observer) → IStreamHandle
│   ├── [I] IAudioTranscriptionModel       →  语音识别：音频 → 文本
│   ├── [I] ISpeechSynthesisModel          →  语音合成：文本 → 音频资产
│   └── [I] IVideoGenerationModel          →  视频异步任务：submit / get / cancel（返回 MediaJob）
├── 【流契约与流事件】
│   ├── [I] IImageGenerationObserver       →  图片流事件回调：onEvent(ImageGenerationEvent)
│   └── [C] ImageGenerationEvent           =  一次图片流事件：图片已生成 / 单张失败 / 收尾 / 错误；
│                                              ★ 批次 8 计划新增字段 log: RequestLogEntry（Q18，未开工）
├── 【请求基类】
│   ├── [C] ImageRequest                   →  图片请求基类：prompt / count / 尺寸或比例 / 质量 / 风格 / 种子 / 负向提示 / 源图 / 蒙版
│   ├── [C] VideoRequest                   →  视频请求基类：参考图 / 首尾帧 / 时长 / 尺寸 / 比例 / 帧率 / 种子 / 负向提示
│   ├── [C] AudioTranscriptionRequest      →  语音识别输入：音频资产、提示、语言、温度、响应格式
│   └── [C] SpeechSynthesisRequest         →  语音合成输入：文本、音色、语言、输出类型、采样率、语速
├── 【响应基类】
│   ├── [C] ImageResponse                  →  生图结果：assetIds / requestId（原 ImageGenerationResult）
│   ├── [C] VideoResponse                  ★ jobId / status / assetIds（现在只有 MediaJob，缺统一响应）
│   ├── [C] TranscriptionResponse          →  转写结果：文本 / 语种 / 时长 / 分段（原 AudioTranscriptionResult）
│   └── [C] SpeechResponse                 →  合成结果：音频资产 ID / 时长（原 SpeechSynthesisResult）
├── [C] TranscriptionSegment               →  转写分段：起止毫秒 + 文本
└── [N] MediaRequestValues                 →  媒体请求公共校验（必填字符串、资产 ID 列表；包私有）

com.clara.enums（Q14：全部枚举集中在此，**不在 `com.clara.model.api`**）
├── [E] ModelProvider                      =  平台：OPENAI / VOLCENGINE / DEEPSEEK / ALIBABA / ANTHROPIC
├── [E] ModelCapability                    =  能力（13 项，Q29 移除 `EMBEDDING`）：文本、多模态输入、工具调用、生图、批量、编辑、蒙版、转写、合成、视频、参考图…
├── [E] Role                               =  角色：SYSTEM / USER / ASSISTANT / TOOL
├── [E] FinishReason                       =  结束原因：STOP / LENGTH / TOOL_CALLS / CONTENT_FILTER / CANCELLED / ERROR
├── [E] ModelModality                      ←  从 clara-model 的 runtime 移入（批次 4-2a）：TEXT / IMAGE / AUDIO / VIDEO / DOCUMENT
├── [E] ModelExecutionMode                 ←  从 runtime 移入（批次 4-2a）：SYNCHRONOUS / STREAMING / ASYNCHRONOUS
├── [E] ModelMetadataSource                ←  从 runtime 移入（批次 4-2a）：元数据来源及优先级
├── [E] CapabilityKind                     ★ 能力种类（Q29 移除 `EMBEDDING`）：CHAT / IMAGE / VIDEO / AUDIO_TRANSCRIPTION / SPEECH
├── [E] StreamEventType                    ★ 由 `StreamEvent.Type` 提升为顶层枚举（批次 4-1）；★ 批次 8 计划新增取值 LOG（非终态，Q18）
├── [E] ImageGenerationEventType           ★ 由 `ImageGenerationEvent.Type` 提升为顶层枚举（批次 4-1）；★ 批次 8 计划新增取值 LOG（Q18）
├── [E] ImageStreamDeltaKind               ★ 由流增量的内部 `Kind` 提升为顶层枚举（批次 5/6 image）
└── [C] ClaraException                     =  承载 ClaraError 的运行时异常（**位于 `com.clara.enums`，归属待确认**）

com.clara.kernel / com.clara.tool（内容、请求、响应、参数与用量 DTO）
├── [C] ChatMessage                         →  一条消息：角色 + 内容列表 + 名称 + 元数据
├── [C] TextContent                         →  纯文本内容：getType() = "text" + getText()
├── [C] ChatRequest                         →  对话请求基类：messages / parameters / tools / clearTools（可实例化）
├── [C] ChatResponse                        →  对话响应：message / tokenUsage / finishReason
├── [C] ModelParameters                     →  采样参数与覆盖：model / temperature / topP / topK / maxTokens / penalty / responseFormat / stops
├── [C] ToolCallContent（`com.clara.tool`）  →  工具调用内容：id / 函数名 / 参数 JSON
├── [C] TokenUsage（`com.clara.tool`）       →  token 用量：输入 / 输出 / 总量 / 缓存命中
└── [C] ToolSpecification（`com.clara.tool`）→  工具声明（名称、描述、JSON Schema）
```


#### C. `clara-model` · `com.clara.model.request` — **平台派生请求**（基类在 core 的 `api`）

```
com.clara.model.request
├── compatible/
│   ├── [C] OpenAiCompatibleChatRequest   ★ 兼容家族扩展：reasoningContent / includeUsage / stopSequences
│   ├── deepseek/[C] DeepSeekChatRequest  ★ DeepSeek 前缀补全（开启时要求末条为 assistant）
│   ├── volcengine/[C] VolcengineChatRequest ★ 方舟深度思考开关
│   └── alibaba/[C] AlibabaChatRequest    ★ 通义思考与联网搜索开关
├── anthropic/
│   └── [C] AnthropicChatRequest          ★ system 独立字段、maxTokens 必填、思考预算
├── image/
│   ├── compatible/[C] OpenAiCompatibleImageRequest ★ responseFormat / watermark / quality / style
│   ├── openai/[C] OpenAiImageRequest     ★ 编辑接口走 multipart 上传
│   ├── volcengine/[C] VolcengineImageRequest ★ 方舟：response_format=url、URL 传源图、不支持蒙版
│   └── alibaba/[C] AlibabaImageRequest   ★ 阿里原生异步任务：outputType / outputMimeType
└── video/
    ├── volcengine/[C] VolcengineVideoRequest ★ 方舟内容生成任务：ratio / 首尾帧
    └── alibaba/[C] AlibabaVideoRequest   ★ 阿里原生异步视频任务
```

> 业务方（`clara-novel` 等）继续 `new ChatRequest(...)` 即可——基类在 core，不需要知道平台；只有需要平台专属字段时才用上表子类。

#### D. `clara-model` · `com.clara.model.response` — **平台派生响应**（基类在 core 的 `com.clara.model.api` / `api.media`）

> **本组 ✅ 已落地**（批次 4-2a + 5/6）：图片线的平台派生响应由 codec 的 `response(payload, assetIds, config)` 组装（落盘是模型职责，见 §6.1）。

```
com.clara.model.response
├── compatible/
│   ├── [C] OpenAiCompatibleChatResponse  ★ id / model / systemFingerprint / reasoningContent（原塞在 metadata 里）
│   └── deepseek/[C] DeepSeekChatResponse ★ 推理内容提升为显式字段
├── anthropic/
│   └── [C] AnthropicChatResponse         ★ stopSequence / 缓存创建与命中 token
├── image/
│   ├── compatible/[C] OpenAiCompatibleImageResponse ★ 原始 data[] 条目（url / b64_json）
│   ├── volcengine/[C] VolcengineImageResponse ★ 用量：generatedImages / outputTokens / webSearchCount
│   └── alibaba/[C] AlibabaImageResponse  ★ taskId / taskStatus
└── video/
    ├── volcengine/[C] VolcengineVideoResponse ★ taskId
    └── alibaba/[C] AlibabaVideoResponse  ★ taskId / outputMimeType
```

> codec 返回平台子类，调用方按 core 的 `ChatResponse` / `ImageResponse` 基类读取，因此上层不感知平台。


#### E. `clara-model` · `com.clara.model.status` — 临时状态（单次调用内）

```
com.clara.model.status（✅ 批次 1 + 批次 5/6 已落地）
├── [C] StreamHandleState                 ★→ 流句柄状态：CallContext + Future，cancel() / isDone()（实现 IStreamHandle）
├── [C] ToolCallState                     ★→ 单个工具调用的 id / name / arguments 片段缓冲
├── [C] ChatStreamState                   ★ 兼容对话流状态：文本与推理缓冲、工具状态、跨块一致字段（id / model / fingerprint）、用量、累计字符数、sawText 标志、是否收尾
├── [C] AnthropicStreamState              ★ Anthropic 流状态：id / model / stopSequence、当前内容块集合、停止原因、用量、累计字符数
├── [C] AnthropicContentBlockState        ★→ 原 `AnthropicStreamAccumulator.Block` 摊平：块类型 + 文本缓冲 + id / name + stopped 标记
├── [C] ImageStreamState                  ★ 生图流状态：已落盘资产、失败序号、元数据、requestId、是否收尾
└── ✗ VideoJobState                       未创建（视频线未开工，仍是轮询局部变量）
```

#### F. `clara-model` · `com.clara.model.http` — 公共 HTTP 层

```
com.clara.model.http
├── [I] IHttpTransport                    ★→ 传输契约：execute(request, call) / execute(request, call, listener)
├── [I] ICallSpec<I>                      ★→ 请求侧描述：endpoint / payload / method / contentType / accept / authentication / additionalHeaders
├── [I] ICallExchange<I,O>                ★→ ICallSpec + decode(response, request)
├── [I] ICredentialResolver               ★→ resolve(conversation, secretRef) → char[]（用后清零）
├── [C] ModelInvoker                      ★→ 唯一入口：解析凭据 → 组装请求头 → 发送 → 状态码映射（三种用法）
├── [C] RawCallSpec                       ★→ 命令式往返的请求描述（原 `ProviderInvoker.RawPlan` 摊平；包私有）
├── [C] HttpRequest                       ★→ 请求值对象：method / endpoint / headers / body
├── [C] HttpResponse                      ★→ 响应值对象：statusCode / headers / body
├── [C] HttpTransportConfig               ★→ 白名单 / 连接与读取超时 / 收发体积上限 / 平台默认头；allows(uri)
├── [C] RequestLogEmitter                ★ Q18/Q20 日志发射器：按生效级别组装 RequestLogEntry，同时走 SLF4J 与观察者；
│                                          关闭档不组装、不调用 logger（零开销）；isEnabled()
├── sse/
│   ├── [I] ISseListener                  ★→ SSE 事件回调：onEvent(SseEvent)，返回 false 提前结束
│   ├── [C] SseEvent                      ★→ 一个 SSE 事件块：event / data / id / retryMillis
│   ├── [C] SseParser                     ★→ SSE 字节流解析：字段拼接、注释、CRLF、BOM + 取消/超时/四项上限
│   └── [N] SseState                      ★→ 单个事件解析过程中的可变状态（包私有）
└── impl/
    └── [C] Jdk8HttpTransport             ★→ 基于 HttpURLConnection 的传输实现（同步 + SSE），负责默认头与自带头合并
```

#### G. `clara-model` · `com.clara.model.configuration` — 平台级配置 + 能力配置继承树 + 解析器

```
com.clara.model.configuration
├── [C] ProviderConfiguration             =  平台级配置：provider / credential / transport / capabilities（Closeable，级联关凭据）
├── [C] ProviderCapabilityConfiguration   =  YAML 能力节点：endpoint / taskQueryEndpoint / modelId / apiProfile / parameters
├── [C] ProviderCredentialConfiguration   =  凭据：环境变量或内联密钥，resolver() / close() 清零
├── [C] ProviderTransportConfiguration    =  平台传输配置 → HttpTransportConfig；★ 新增可选字段 logLevel（覆盖全局，Q19）
├── [C] ObservabilityConfiguration        ★ Q19 全局日志默认：logLevel / maxBodyChars（根节点 observability）
├── [C] ProviderYamlConfigurationLoader   →  退化为门面（Q24~Q27）：`load(Path)` 内部只调 `StrictYamlBinder`；
│                                           4 张白名单 Set 与逐字段解析方法删除，配置形状改由配置类上的注解声明
├── [C] StrictYamlBinder                 ★ Q24 严格绑定器：文件校验 / 单文档 / 重复键 / 未知项拒绝 /
│                                           枚举大小写不敏感 / Jackson 异常 → 带字段路径的中文提示
├── deserialize/                         ★ Q25 保住原手工校验的三个自定义反序列化器（见 §5.4）
│   ├── [C] SafeHttpUriDeserializer       ★ 安全绝对 HTTP(S) URI（无 userinfo）
│   ├── [C] ScalarMapDeserializer         ★ 扩展参数只允许标量
│   └── [C] SecretCharsDeserializer       ★ 内联密钥 YAML 字符串 → char[]（Jackson 默认处理与密钥语义不一致）
├── config/                               能力配置继承树（批次 3 ✅ 已落地：旧配置类改建为新体系子类，构造器参数等价，调用点一次改完）
│   ├── [A] ModelConfig                   ★ 基类：provider / endpoint / modelId / apiProfile / secretRef
│   ├── [A] ChatModelConfig               ★ + defaultMaxTokens
│   │   ├── compatible/[C] OpenAiCompatibleChatConfig  ★→ 供 OPENAI / DEEPSEEK / VOLCENGINE / ALIBABA 复用
│   │   └── anthropic/[C] AnthropicChatConfig          ★→ + apiVersion
│   ├── [A] ImageModelConfig              ★ + taskQueryEndpoint / maxPromptChars / maxOutputs / maxSourceImages / maxInputBytes；构造器含 apiProfile（取自能力节点同名键）
│   │   ├── compatible/[C] OpenAiCompatibleImageConfig ★→ 供 OPENAI / VOLCENGINE 复用（**非 final**，供 OPENAI 特化）
│   │   ├── openai/[C] OpenAiImageConfig               ★ + maxInputBytes > 0（表示走 multipart 编辑）
│   │   └── alibaba/[C] AlibabaImageTaskConfig         ★ + outputType / outputMimeType
│   ├── [A] VideoModelConfig              ★ + taskQueryEndpoint / maxPromptChars / maxReferenceImages
│   │   ├── volcengine/[C] VolcengineVideoConfig       ★→
│   │   └── alibaba/[C] AlibabaVideoTaskConfig         ★
│   └── [A] AudioModelConfig              ★ + maxTextChars / maxInputBytes / maxOutputs
│       ├── openai/[C] OpenAiAudioConfig               ★→ 由 OpenAiMediaConfig 拆分（转写与合成共用）
│       └── alibaba/[C] AlibabaAudioConfig             ★→
└── resolver/                             配置解析（替换 8 个 toXxxConfig）
    ├── [I] IModelConfigResolver          ★ supports(provider, kind) / resolve(providerConfig, capabilityName, kind)
    │                                       （入参的 `CapabilityKind` 是枚举，按 Q14 定义在 **core 的 `com.clara.model.api`**，不在本包）
    ├── [C] OpenAiCompatibleConfigResolver ★ 兼容家族（OPENAI / DEEPSEEK / VOLCENGINE / ALIBABA）的 chat
    ├── [C] OpenAiConfigResolver          ★ OPENAI 的 image（multipart）与 audio
    ├── [C] VolcengineConfigResolver      ★ VOLCENGINE 的 image / video（不覆盖 chat，避免重复注册）
    ├── [C] AnthropicConfigResolver       ★ ANTHROPIC 的 chat
    ├── [C] AlibabaConfigResolver         ★ ALIBABA 的 image / video / audio（不覆盖 chat，避免重复注册）
    └── [C] ModelConfigResolvers          ★ 静态注册表：forProvider(ModelProvider, CapabilityKind) 按「平台 × 能力」查找；
                                            组合唯一在类初始化期校验，不支持时抛 CAPABILITY_NOT_SUPPORTED
```

#### H. `clara-model` · `com.clara.model.codec` — 协议编解码（继承式平台差异）

```
com.clara.model.codec
├── compatible/                           兼容家族模板层（非 final，暴露 protected 钩子）
│   ├── [C] OpenAiCompatibleChatCodec     →  同步编解码 + applyProviderFields 钩子（平台字段在此写入）
│   ├── [C] OpenAiCompatibleChatStreamReader ★ 流式读流：SSE 事件 → ChatStreamState → 增量
│   ├── [C] OpenAiCompatibleImageCodec    →  JSON / multipart 两条编码路径 + 三个钩子；响应信封解析
│   ├── [C] OpenAiCompatibleImageStreamReader ★ 流式读流：image_generation.* 事件 → 增量
│   ├── [C] ImageCodecOutput              ★→ 一条图片输出：远程 URL 或内联字节（原 codec 内部类 `Output` 摊平）
│   ├── [C] ImageCodecPayload             ★→ 一次响应解析结果：输出列表 + 平台请求标识（原内部类 `Decoded` 摊平）
│   ├── [C] ChatStreamDelta               ★→ 对话增量产物：文本 / 推理 / 工具片段 / 终态响应（原 StreamChunk）
│   ├── [C] ToolDelta                     →  单个工具调用本次新增的片段（index + id/name/arguments）
│   └── [C] OpenAiCompatibleReasoning     →  reasoning_content 的字段名与元数据键常量
├── deepseek/
│   └── [C] DeepSeekChatCodec             ★ 写 prefix / reasoning_content 到末条 assistant 消息（原 DeepSeekChatOptions）
├── volcengine/
│   ├── [C] VolcengineChatCodec           ★ 思考开关、stream_options 细节
│   ├── [C] VolcengineImageCodec          ★ response_format=url / watermark / seed / negative_prompt / image URL
│   └── [C] VolcengineImageStreamReader   ★ 用量字段入状态（原 VolcengineImageOptions 的手工映射）
├── openai/
│   └── [C] OpenAiImageCodec              ★ b64_json / quality / style；编辑走 multipart（原 OpenAiImageOptions）
├── alibaba/
│   └── [C] AlibabaChatCodec              ★ enable_thinking / enable_search
└── anthropic/                            独立协议体系
    ├── [C] AnthropicChatCodec            →  system 独立字段、max_tokens 必填、工具 schema 转换、stop_reason 映射
    ├── [C] AnthropicStreamReader         ★ 按事件名分派：message_start / content_block_delta / message_delta / message_stop
    └── [C] AnthropicStreamDelta          ★→ Anthropic 增量产物（原 AnthropicStreamChunk）
```

#### I. `clara-model` · `com.clara.model.provider` — 模型实现与媒体支撑

```
com.clara.model.provider
├── compatible/
│   ├── [C] OpenAiCompatibleChatModel            =  同步对话：校验 → 编码 → 一次往返 → 解析（构造器注入平台 codec）
│   ├── [C] OpenAiCompatibleStreamingChatModel   =  流式对话：提交执行器 + 按序推送流事件
│   ├── [C] OpenAiCompatibleStreamAccumulator    ✗ 删除 → 拆为 codec 的 ChatStreamReader + status 的 ChatStreamState
│   ├── [I] OpenAiCompatibleOptions              ✗ 删除（Q2）
│   ├── handler/[C] ChatStreamEventEmitter       ★→ 对话流事件发射器（合并原 `handler.EventEmitter` 与 `AnthropicStreamingChatModel.Emitter`）
│   ├── handler/[C] ImageGenerationEventEmitter  ★→ 图片流事件发射器（原 `OpenAiCompatibleStreamingImageModel.ImageEventEmitter` 摊平）
│   └── image/
│       ├── [C] OpenAiCompatibleImageModel        =  同步生图：按 codec 的 usesMultipart 选路径，逐条落盘后汇总
│       ├── [C] OpenAiCompatibleStreamingImageModel = 流式生图：逐张落盘即回调，单张失败不中断
│       ├── [C] OpenAiCompatibleImageValidator    =  前置校验：能力、平台归属、提示词/数量/源图上限、平台限制
│       ├── [C] OpenAiCompatibleImageStreamPlan   ✗ 删除 → 由 codec 的 ICallSpec 承担
│       ├── [C] OpenAiCompatibleImageStreamAccumulator ✗ 删除 → 拆为 codec 的 ImageStreamReader + status 的 ImageStreamState
│       ├── [C] OpenAiCompatibleImageConfig       →  迁入 configuration.config.compatible
│       └── [I] OpenAiCompatibleImageOptions      ✗ 删除（Q2）
├── anthropic/
│   ├── [C] AnthropicChatModel                   =  同步对话
│   ├── [C] AnthropicStreamingChatModel          =  流式对话
│   ├── [C] AnthropicChatExchange                =  一次 Anthropic 往返的描述（端点是配置、Accept 按流式、x-api-key 鉴权）
│   ├── [C] AnthropicChatConfig                  →  迁入 configuration.config.anthropic（+ apiVersion）
│   ├── [C] AnthropicStreamAccumulator           ✗ 删除 → 拆为 codec 的 AnthropicStreamReader + status 的 AnthropicStreamState
│   └── [C] AnthropicStreamChunk                 ✗ 删除 → codec.anthropic.AnthropicStreamDelta
├── deepseek/
│   └── [C] DeepSeekChatOptions                  ✗ 删除 → codec.deepseek.DeepSeekChatCodec
├── openai/
│   ├── [C] OpenAiAudioTranscriptionModel        =  语音识别（multipart 上传音频）
│   ├── [C] OpenAiSpeechSynthesisModel           =  语音合成（二进制响应落盘为资产）
│   ├── [C] OpenAiImageOptions                   ✗ 删除 → codec.openai.OpenAiImageCodec
│   └── [C] OpenAiMediaConfig                    ✗ 删除 → configuration.config.openai.OpenAiAudioConfig
├── volcengine/
│   ├── [C] VolcengineVideoJobFactory            =  创建视频异步任务操作（提交 / 轮询 / 取消）
│   ├── [C] VolcengineVideoJobOperation          ★→ 方舟视频任务操作实现（原工厂内部类 `Operation` 摊平）
│   ├── [C] VolcengineImageOptions               ✗ 删除 → codec.volcengine.VolcengineImageCodec
│   └── [C] VolcengineVideoConfig                →  configuration.config.volcengine.VolcengineVideoConfig
├── alibaba/
│   ├── [C] AlibabaNativeMediaJobFactory         =  创建图片/视频异步任务操作（原生异步接口）
│   ├── [C] AlibabaNativeMediaJobOperation       ★→ 阿里原生任务操作实现（原工厂内部类 `Operation` 摊平）
│   ├── [C] AlibabaAudioTranscriptionModel       =  语音识别
│   ├── [C] AlibabaSpeechSynthesisModel          =  语音合成
│   ├── [C] AlibabaAudioConfig                   →  configuration.config.alibaba.AlibabaAudioConfig
│   └── [C] AlibabaNativeTaskConfig              ✗ 删除 → 拆为 configuration.config.alibaba 的两个任务配置
└── media/                                        媒体支撑（本次不改）
    ├── [I] IProviderMediaAssetBridge            →  媒体资产桥：inputReference / inputBytes / storeRemote / remoteUpload / storeBytes
    ├── [C] MediaJson                            =  与平台绑定的 JSON 读写（失败归因到平台）
    ├── [C] MultipartBody                        =  multipart/form-data 构造器（text / file / contentType / finish）
    └── [A] AbstractJsonMediaJobOperation        =  异步媒体任务模板：submit / poll / cancel + 平台 JSON 钩子
```

#### J. `clara-model` · `com.clara.model.runtime`（逻辑本次不动，命名也不加 `I` 前缀）

```
com.clara.model.runtime
├── [C] ModelRegistry                     =  以 (provider, alias) 注册与查找模型；重复注册报错
├── [C] RegisteredModel                   =  一个已注册模型：描述符 + 同步/流式对话端点（Q29 移除嵌入端点）
├── [N] ModelKey                          =  注册键（provider + alias，包私有）
├── [C] ProviderModelDescriptor           =  模型身份与能力集合：modelId / alias / apiProfile / region / revision / capabilities
├── [C] ProviderModelMetadata             =  模型元数据：模态、上下文窗口、参数白名单、执行模式、定价版本
├── [C] ProviderModelMetadataBuilder      ★→ 元数据构造器（原 `ProviderModelMetadata.Builder` 摊平为顶层类）
├── [C] ModelCapabilityGuard              =  按请求推导所需能力并强制校验（批量、编辑、蒙版、参考图等）
├── [C] ModelParameterOverlay             =  模型默认参数与请求参数的覆盖合并
└── [N] RuntimeErrors                     =  运行时错误工厂（配置类、能力类；包私有）

  注 1：`ModelExecutionMode` / `ModelModality` / `ModelMetadataSource` 三个枚举按 Q14 **已移入 core 的 `com.clara.enums`**（批次 4-2a），
        `clara-model` 内不再保留任何枚举。
  注 2：原 `com.clara.model.utils` **已整体上移**到 `clara-core` 的 `com.clara.utils`（Q30~Q32，见 A 组）。
```

> **关于批次 5 的少量细节**：`StreamChunk` / `ToolDelta` / `OpenAiCompatibleReasoning` / `AnthropicStreamChunk` /
> `OpenAiCompatibleImageStreamPlan` 这几个类型在批次 5 拆 codec 时才会着手改造（上表已给出目标位置与摊平后的名字）。
> 其余类型的位置与名称均已定稿。
>
> **规则二 / 规则三带来的三处结构性收益**（不是单纯改名）：
> 1. `AnthropicStreamingChatModel.AsyncHandle` 被删除——它与批次 1 已建的 `status.StreamHandleState` 是同一实现；
> 2. 三个流事件发射器（`handler.EventEmitter`、`AnthropicStreamingChatModel.Emitter`、`OpenAiCompatibleStreamingImageModel.ImageEventEmitter`）
>    收敛为两个顶层类：`ChatStreamEventEmitter` / `ImageGenerationEventEmitter`；
> 3. codec 的内部类 `Output` / `Decoded` 变成顶层 `ImageCodecOutput` / `ImageCodecPayload` 后，
>    可以同时被同步与流式两条路径复用（原先它们被锁在 codec 内部）。

### 0.3 包级依赖方向

```
clara-core：context / kernel(.exception) / model.api(.media) / enums / media(.job) / tool / utils  ◀── 全模块可依赖（utils 由 Q30~Q32 上移至此）
──────────────────────────────────────────
runtime ──▶ http
                ▲         configuration ──▶ resolver
request/response/status ────────┘   │
        ▲                           ▼
        └──── codec ◀──── provider（唯一认识「平台 × 能力」组合的层）
```

- `request` / `response` / `status`：纯数据，只依赖 `clara-core` 的 `com.clara.context`（`ConversationContext`、`CallContext`、`CancellationToken`）、`com.clara.kernel` / `kernel.exception`（`ClaraError`）、`com.clara.enums`、`media`、`tool`（Q1 结论：这些包本身住在 `clara-model`，只**使用** core 的基础类型；`message` 包作废）。
- `http`：不依赖 request / configuration / provider。
- `codec.compatible`：兼容家族模板，只处理通用字段；平台差异由 `codec.<platform>` 的派生类覆盖 `protected` 钩子实现，因此 `codec.compatible` 仍不认识具体平台（见 §6 与配套文档 §3.5）。
- `provider`：唯一可以同时 import 平台配置、请求子类与 codec 的层。

---

## 1. `http` — 公共 HTTP 层

职责：把「取消检查 → 凭据解析与清零 → 组装请求头 → 发送 → 状态码映射」收敛为唯一入口，**支持同步与流式两种通道**；不感知任何能力与平台。

### 1.1 结构

```
http/
├── IHttpTransport (接口)             ★→  原 ProviderHttpTransport
├── ICallSpec<I> (接口)               ★→  原 ProviderRequestPlan
├── ICallExchange<I,O> (接口)         ★→  原 ProviderExchange
├── ModelInvoker (final)              ★→  原 ProviderInvoker
├── ICredentialResolver (接口)        ★→  原 ProviderCredentialResolver
├── HttpRequest (值对象)              ★→  原 impl.ProviderHttpRequest
├── HttpResponse (值对象)             ★→  原 impl.ProviderHttpResponse
├── HttpTransportConfig (值对象)      ★→  原 impl.ProviderHttpTransportConfig
├── sse/
│   ├── ISseListener (接口)           ★→  原 ProviderSseListener
│   ├── SseEvent (值对象)             ★→  原 event.ProviderSseEvent
│   └── SseParser (final)             ★→  原 event.ProviderSseParser
└── impl/
    └── Jdk8HttpTransport (final)     ★→  原 impl.Jdk8ProviderHttpTransport
```

> **批次 2 ✅ 已落地**：本包由 `com.clara.model.provider.http`（含 `event` / `impl`）整体上移并改名而来，旧包与上表「原 XXX」旧名全仓 0 残留；包内另有包私有顶层 `RawCallSpec`（原 `ProviderInvoker.RawPlan` 摊平）与 `sse.SseState`。

### 1.2 类与方法

| 类 | 主要方法 | 职责 |
| --- | --- | --- |
| `IHttpTransport` | `HttpResponse execute(HttpRequest, CallContext)`；`HttpResponse execute(HttpRequest, CallContext, ISseListener)` | 传输契约：两参是非流式，三参是流式（响应体交给监听器消费）。它只需要「取消 / 超时」，因此收 `CallContext` |
| `ICallSpec<I>` | `URI endpoint(I)`；`byte[] payload(I)`；`String method(I)`；`String contentType(I)`；`String accept(I)`；`Map<String,String> authentication(String secret, I)`；`Map<String,String> additionalHeaders(I)` | 描述一次往返的**请求侧**；除 `endpoint`/`payload` 外都有默认实现（POST / JSON / Bearer） |
| `ICallExchange<I,O>` | `O decode(HttpResponse, I)`（继承 `ICallSpec`） | 在请求侧之上补**响应解析**，用于「同步往返一步到位」 |
| `ModelInvoker` | `O exchange(I, ConversationContext, CallContext, ICallExchange<I,O>)`；`HttpResponse exchangeStreaming(I, ConversationContext, CallContext, ICallSpec<I>, ISseListener)`；`HttpResponse execute(String method, URI, String contentType, byte[] body, Map<String,String> extraHeaders, ConversationContext, CallContext)` | 唯一构造 `HttpRequest` 的类：按会话解析凭据并在 `finally` 清零、按序合并请求头、发送、把非 2xx 映射为可重试语义的领域异常。三个方法分别对应**声明式同步 / 声明式流式 / 命令式（轮询、取消）** |
| `ICredentialResolver` | `char[] resolve(ConversationContext, String secretRef)` | 按会话（租户）与引用名换取密钥；实现方负责不缓存明文 |
| `HttpRequest` | `getMethod()`；`getEndpoint()`；`getHeaders()`；`getBody()` | HTTP 请求值对象（不可变） |
| `HttpResponse` | `getStatusCode()`；`getHeaders()`；`getBody()` | HTTP 响应值对象；流式成功时 `body` 为空 |
| `HttpTransportConfig` | `boolean allows(URI endpoint)`；`getAllowedOrigins()`；`getConnectTimeoutMillis()`；`getReadTimeoutMillis()`；`getMaxRequestBytes()`；`getMaxResponseBytes()`；`isAllowHttp()`；`getHeaders()` | 白名单与资源上限；`allows` 做源站白名单校验（不跟随重定向） |
| `sse.ISseListener` | `boolean onEvent(SseEvent)` | SSE 事件回调；返回 `false` 表示提前结束流 |
| `sse.SseEvent` | `getEvent()`；`getData()`；`getId()`；`getRetryMillis()` | 一个完整 SSE 事件块；`event` 缺失时为默认事件名 `message` |
| `sse.SseParser` | `int parse(InputStream, CallContext, ISseListener)` | 按 SSE 规范逐行解析（字段拼接、注释忽略、CRLF、BOM），执行取消/超时与四项体积上限检查；**流尾未收尾的事件块按规范丢弃** |
| `impl.Jdk8HttpTransport` | 实现 `IHttpTransport` 的两个 `execute` | 基于 `HttpURLConnection` 的传输实现；负责把「配置的默认头」与「请求自带头」合并（请求头优先，同名忽略大小写） |

> **上下文传递规则（Q13 的落地约束）**：SPI 与工具方法**不再接收「组合上下文」**，而是按实际需要接收**两个独立参数**——
> 需要会话信息（租户、会话 ID）传 `ConversationContext`，需要本次调用的生命周期（trace、超时、取消）传 `CallContext`；
> 只需其一的场景就只传那一个（`IHttpTransport` / `SseParser` 只需 `CallContext`）。这样「会话」与「请求」在类型系统里不可能被混用。

---

## 2. `request` — 请求侧

### 2.1 继承树

```
ModelRequest（core: com.clara.model.api）                          ★ 根基类
├── ChatRequest（core: com.clara.kernel）                          ★→ 能力基类在 core
│   ├── compatible.OpenAiCompatibleChatRequest                     ★ 兼容家族扩展
│   │   ├── compatible.deepseek.DeepSeekChatRequest                 ★
│   │   ├── compatible.volcengine.VolcengineChatRequest             ★
│   │   └── compatible.alibaba.AlibabaChatRequest                   ★
│   └── anthropic.AnthropicChatRequest                              ★
├── api.media.AudioTranscriptionRequest（core，无平台子类）          ★
├── api.media.SpeechSynthesisRequest（core，无平台子类）             ★
├── api.media.ImageRequest（core）                                   ★ 原 ImageGenerationRequest
│   ├── image.compatible.OpenAiCompatibleImageRequest               ★
│   │   ├── image.openai.OpenAiImageRequest                         ★
│   │   └── image.volcengine.VolcengineImageRequest                 ★
│   └── image.alibaba.AlibabaImageRequest                           ★
└── api.media.VideoRequest（core）                                   ★ 原 VideoGenerationRequest
    ├── video.volcengine.VolcengineVideoRequest                      ★
    └── video.alibaba.AlibabaVideoRequest                            ★
```

> 能力基类是否抽象见 **Q3**；推荐**可实例化**，平台子类作为可选强化（业务方不必知道平台）。

### 2.2 `ModelRequest`（根基类，位于 core 的 `com.clara.model.api`）

| 方法 | 职责 |
| --- | --- |
| `getConversation()` | **会话绑定**：`ConversationContext`（租户、用户、会话 ID），跨请求稳定 |
| `getCall()` | **本次请求**：`CallContext`（traceId、截止时间、取消令牌），每次请求新建 |

> **现状修正**：`ModelRequest` 只有上面两个字段（`conversation` + `call`）；设计期的 `modelId` 覆盖与 `provider` 声明**两个槽位已移除**——模型 ID 由 `ModelParameters.model` 承载，平台由配置 + 请求子类类型（入口 `codec.adapt` 适配）决定。

设计要点：构造器做必填校验（`conversation`、`call` 均不可 null）；`@Getter` + `@EqualsAndHashCode`，**不生成 setter**，保持不可变。

会话与请求两个上下文由 `clara-core` 的 `com.clara.context` 提供，**不随请求/响应一起迁移**（它们是跨模块共享的内核类型）。**✅ 批次 0 已按此落地**：

| 类型 | 字段 | 职责 |
| --- | --- | --- |
| `ConversationContext` | `tenantId`（必填）、`userId`、`conversationId`（必填） | 绑定当前 chat 会话；`conversationId` 与 `clara-memory` 的 `ConversationStore` / `ChatMemory` / `SemanticMemoryQuery` 同源同值，模型层据此把请求关联到会话历史 |
| `CallContext` | `traceId`（必填）、`deadlineEpochMillis`、`cancellationToken`（必填）；`long remainingMillis(long now)` | 一次调用的标识与生命周期；**traceId 每次请求重新生成**，同一会话内不同请求必须不同 |

两个类型的构造器都做必填校验（空白 / null 直接抛 `IllegalArgumentException`）。

**没有派生方法**（Q22）：不提供 `child(...)`，子任务要换截止时间或跟踪标识时显式新建 `CallContext`，旧 `child()` 的继承语义改为在调用点显式表达。

**没有真实对话的调用**（Q23）：`conversationId` 必填，后台轮询 / 媒体任务这类调用用任务 ID 等**稳定的语义值**自造会话 ID。

`runId` / `instanceId` **不在上下文里**——它们属于工作流 / 技能实例维度，由需要它们的模块自己的 DTO 携带。批次 0 实测的唯一落点是 `clara-skill` 的 `SkillExecutionRequest`（新增自有字段，`GeneralWorkspaceSkillHandler` 改读它）；`clara-orchestration` 的 `InteractionRequest` 委派的是它自己的 `InteractionContext`，与上下文无关，**无需改动**。
详见配套文档 §2.4 与 §11-Q13 / Q22 / Q23。

### 2.3 各请求类

> 位置说明：能力基类在 core（对话在 `com.clara.kernel`，媒体在 `com.clara.model.api.media`），平台派生类在 `com.clara.model.request`；`request` 包下**没有 audio 子包**（音频请求没有平台子类）。

| 类 | 新增字段（相对父类） | 主要方法 | 职责 |
| --- | --- | --- | --- |
| `kernel.ChatRequest` | `messages`、`parameters`、`tools`、`clearTools` | `getMessages()`；`getParameters()`；`getTools()`；`isClearTools()` | 对话能力基类；校验「消息非空、不含 null、工具非 null」 |
| `api.media.AudioTranscriptionRequest` | `audioAssetId`、`prompt`、`language`、`temperature`、`responseFormat` | `getAudioAssetId()` 等 | 语音识别输入（无平台子类） |
| `api.media.SpeechSynthesisRequest` | `text`、`voice`、`language`、`outputMimeType`、`sampleRateHz`、`speed` | `getText()` 等 | 语音合成输入（无平台子类） |
| `api.media.ImageRequest` | `prompt`、`sourceImageAssetIds`、`maskAssetId`、`count`、`width`、`height`、`aspectRatio`、`quality`、`style`、`seed`、`negativePrompt` | `isEdit()`；`isMaskEdit()` | 图片能力基类；校验「宽高成对、比例与尺寸互斥、蒙版必须有源图」 |
| `api.media.VideoRequest` | `prompt`、`referenceImageAssetIds`、`firstFrameAssetId`、`lastFrameAssetId`、`durationMs`、`width`、`height`、`aspectRatio`、`framesPerSecond`、`seed`、`negativePrompt` | `getPrompt()` 等 | 视频能力基类；异步任务输入 |
| `compatible.OpenAiCompatibleChatRequest` | `reasoningContent`、`includeUsage`、`stream`、`stopSequences` | `getReasoningContent()`；`isIncludeUsage()`；`isStream()` | 兼容家族通用扩展；`includeUsage` 对应 `stream_options.include_usage` |
| `compatible.deepseek.DeepSeekChatRequest` | `prefixCompletion` | `isPrefixCompletion()` | DeepSeek 前缀补全；校验「开启时末条消息必须是 assistant」 |
| `compatible.volcengine.VolcengineChatRequest` | `thinking` | `isThinking()` | 方舟深度思考开关 |
| `compatible.alibaba.AlibabaChatRequest` | `enableThinking`、`enableSearch` | `isEnableThinking()` 等 | 通义思考与联网搜索开关 |
| `anthropic.AnthropicChatRequest` | `system`、`maxTokens`（必填）、`thinkingBudgetTokens` | `getSystem()`；`getMaxTokens()` | Anthropic 的 `system` 不在 messages 中；`max_tokens` 必填 |
| `image.compatible.OpenAiCompatibleImageRequest` | `responseFormat`、`watermark`、`quality`、`style` | `getResponseFormat()` 等 | 兼容图片的通用扩展 |
| `image.openai.OpenAiImageRequest` | `usesMultipartEdit` | `isUsesMultipartEdit()` | 编辑接口需上传文件（multipart） |
| `image.volcengine.VolcengineImageRequest` | `watermark`、`seed`、`negativePrompt`、`imageUrl` | `getWatermark()` 等 | 方舟图片：`response_format=url`、以 URL 传源图、不支持蒙版 |
| `image.alibaba.AlibabaImageRequest` | `outputType`、`outputMimeType` | `getOutputType()` 等 | 阿里原生异步任务（提交 → 轮询） |
| `video.volcengine.VolcengineVideoRequest` | `ratio`、`firstFrame`、`lastFrame` | `getRatio()` 等 | 方舟内容生成任务 |
| `video.alibaba.AlibabaVideoRequest` | `outputType`、`outputMimeType` | `getOutputType()` 等 | 阿里原生异步视频任务 |

> `providerOptions` 字段在新体系下由上述平台子类取代（**Q2**）。**现状**：两个 options 接口与平台专属选项接口均已删除，请求类、codec 与模型类不再保留任何 `providerOptions` 构造参数、槽位或读取调用。

---

## 3. `response` — 响应侧

### 3.1 继承树

```
ModelResponse                                                      ★ 根基类
├── ChatResponse                                                   ★→ core.api.ChatResponse
│   ├── compatible.OpenAiCompatibleChatResponse                     ★
│   │   └── compatible.deepseek.DeepSeekChatResponse                ★
│   └── anthropic.AnthropicChatResponse                             ★
├── audio.TranscriptionResponse                                     ★→ AudioTranscriptionResult
├── audio.SpeechResponse                                            ★→ SpeechSynthesisResult
├── image.ImageResponse                                             ★→ ImageGenerationResult
│   ├── image.compatible.OpenAiCompatibleImageResponse              ★
│   ├── image.volcengine.VolcengineImageResponse                    ★
│   └── image.alibaba.AlibabaImageResponse                          ★
└── video.VideoResponse                                             ★ 新增（现由 MediaJob 承载）
    ├── video.volcengine.VolcengineVideoResponse                    ★
    └── video.alibaba.AlibabaVideoResponse                          ★
```

### 3.2 类与方法

| 类 | 主要方法 | 职责 |
| --- | --- | --- |
| `ModelResponse` | `getProviderMetadata()` | 响应根：平台标识、协议档位、平台原始标识（`request_id`、`id` 等） |
| `ChatResponse` | `getMessage()`；`getTokenUsage()`；`getFinishReason()` | 对话响应基类 |
| `compatible.OpenAiCompatibleChatResponse` | `getId()`；`getModel()`；`getSystemFingerprint()`；`getReasoningContent()` | 把现在散落在 `providerMetadata` 里的兼容字段提升为显式字段 |
| `compatible.deepseek.DeepSeekChatResponse` | `getReasoningContent()`（显式化） | DeepSeek 推理内容 |
| `anthropic.AnthropicChatResponse` | `getStopSequence()`；`getCacheCreationTokens()`；`getCacheReadTokens()` | Anthropic 专有字段；`stop_reason` 已映射为 `FinishReason` |
| `audio.TranscriptionResponse` | `getText()`；`getDetectedLanguage()`；`getDurationMs()`；`getSegments()` | 转写结果；`segments` 为 `TranscriptionSegment` 列表 |
| `audio.SpeechResponse` | `getAudioAssetId()`；`getDurationMs()` | 合成结果（资产 ID 已落盘） |
| `image.ImageResponse` | `getAssetIds()`；`getRequestId()` | 生图结果基类；校验「资产 ID 非空」 |
| `image.compatible.OpenAiCompatibleImageResponse` | `getData()` | 原始 `data[]` 条目（`url` / `b64_json`） |
| `image.volcengine.VolcengineImageResponse` | `getGeneratedImages()`；`getOutputTokens()`；`getWebSearchCount()` | 方舟用量信息（来自流式 `usage`） |
| `image.alibaba.AlibabaImageResponse` | `getTaskId()`；`getTaskStatus()` | 阿里原生异步任务标识与状态 |
| `video.VideoResponse` | `getJobId()`；`getStatus()`；`getAssetIds()` | 视频任务结果；`status` 复用 `MediaJobStatus` |
| `video.volcengine.VolcengineVideoResponse` | `getTaskId()` | 方舟内容生成任务 ID |
| `video.alibaba.AlibabaVideoResponse` | `getTaskId()`；`getOutputMimeType()` | 阿里原生任务 ID 与输出类型 |

### 3.3 消息与内容类型（**Q15 覆盖 Q11/Q4**：留在 `clara-core`，但**实际分散在多个包**，`model.message` 包作废）

```
com.clara.kernel（core）
├── ChatMessage (final)         getRole() / getContents() / getName() / getMetadata()
└── TextContent (final)         getText()；getType() = "text"

com.clara.model.api（core）
├── IContent (接口)             getType()
└── MediaContent (final)        getAssetId()；getType() = mediaType

com.clara.tool（core）
├── ToolCallContent (final)     getId() / getName() / getArguments()
├── TokenUsage (final)          getInputTokens() / getOutputTokens() / getTotalTokens() / getCacheTokens()
└── ToolSpecification (final)   工具声明（名称、描述、JSON Schema）

com.clara.enums（core）
├── FinishReason (枚举)         STOP / LENGTH / TOOL_CALLS / CONTENT_FILTER / CANCELLED / ERROR
└── Role (枚举)                 SYSTEM / USER / ASSISTANT / TOOL

com.clara.model.api.media（core）
└── TranscriptionSegment        getStartMs() / getEndMs() / getText()
```

> 这些类型同时被请求与响应使用，且被能力接口签名引用；若迁到 `clara-model` 会造成 `core → model` 循环依赖（Maven 直接构建失败）。因此按 Q15 全部留在 `clara-core`（分包如上），**`model.message` 包不再需要**（Q11 已据此作废）。

---

## 4. `status` — 临时状态对象

职责：承载**单次调用内**的中间态。判定标准：单次创建、单次丢弃、可变、不跨调用复用。
拆分原则：**`status` 只放字段与 Lombok 注解，不放逻辑**；读事件、更新状态、产出增量的逻辑在 `codec` 与 `provider`。

```
status/
├── StreamHandleState  implements IStreamHandle    ★→ compatible.handler.AsyncStreamHandle        ✅ 批次 1
├── ChatStreamState                               ★→ OpenAiCompatibleStreamAccumulator 的可变字段  ✅ 批次 5/6
├── ToolCallState                                 ★→ compatible.tool.ToolState                    ✅ 批次 1
├── AnthropicStreamState                          ★→ AnthropicStreamAccumulator 的可变字段        ✅ 批次 5/6
├── AnthropicContentBlockState                    ★→ AnthropicStreamAccumulator.Block 摊平        ✅ 批次 5/6
├── ImageStreamState                              ★ 新增（原为模型类局部变量）                     ✅ 批次 5/6
└── ✗ VideoJobState                               未创建（视频线未开工，轮询局部变量仍在模型类内）
```

| 类 | 字段 | 主要方法 | 职责 |
| --- | --- | --- | --- |
| `StreamHandleState` | `CallContext call`；`volatile Future<?> future` | `setFuture(Future<?>)`；`cancel()`；`isDone()` | 流式调用的对外句柄：提交后写入 `Future`；`cancel()` 同时取消调用令牌与中断任务，任一成功即成功。只需要本次调用的取消信号，因此持 `CallContext` |
| `ChatStreamState` | `StringBuilder text / reasoning`；`Map<Integer, ToolCallState> tools`（`TreeMap`）；`String id / model / fingerprint`；`FinishReason finishReason`；`TokenUsage usage`；`int accumulatedChars`；`boolean sawText`；`boolean complete` | Lombok `@Getter` / `@Setter`（含 `isComplete()`） | 兼容对话流的状态容器；跨块一致性校验（id/model/fingerprint 不得变化、usage 不得矛盾）由 `codec` 读写本对象完成 |
| `ToolCallState` | `StringBuilder id / name / arguments` | `getId()` / `getName()` / `getArguments()` | 单个工具调用的片段缓冲（id、函数名、参数 JSON 分片拼接） |
| `AnthropicStreamState` | `Map<Integer, AnthropicContentBlockState> blocks`（`TreeMap`）；`id` / `model` / `stopSequence`；`FinishReason finishReason`；`Integer inputTokens / outputTokens`；`int accumulatedChars`；`boolean started / messageDelta / complete` | Lombok `@Getter` / `@Setter`（含 `isComplete()`） | Anthropic `content_block_*` / `message_delta` 序列的状态 |
| `AnthropicContentBlockState` | `String type`；`StringBuilder value`；`String id / name`；`boolean stopped` | Lombok `@Getter` / `@Setter` | 单个 Anthropic 内容块的状态（原 `AnthropicStreamAccumulator.Block` 摊平为顶层类型） |
| `ImageStreamState` | `List<String> assetIds`；`List<Integer> failedIndices`；`Map<String,String> metadata`；`String requestId`；`boolean complete` | Lombok `@Getter` / `@Setter` | 生图流状态：已落盘资产、失败序号、收尾用量；收尾时由读流器与模型实现据此判断「一张都没成功」 |
| `VideoJobState` | —（未创建） | — | 计划中的异步任务轮询中间态；视频线未开工，目前仍是模型类内的轮询局部变量 |

---

## 5. `configuration` — 配置体系

### 5.1 平台级配置（批次 3 ✅ 已落地：注解绑定 + loader 退化为门面）

```
configuration/
├── ProviderConfiguration            =  平台级：provider / credential / transport / capabilities；★ @JsonCreator + @JsonProperty 声明 YAML 形状
├── ProviderCapabilityConfiguration  =  YAML 原始能力节点（endpoint / modelId / apiProfile / parameters）；★ 同上
├── ProviderCredentialConfiguration  =  凭据（环境变量或内联密钥），Closeable；★ 同上
├── ProviderTransportConfiguration   =  传输配置（与 HttpTransportConfig 一一对应）；★ 同上
└── ProviderYamlConfigurationLoader  →  YAML 加载入口：已退化为门面，内部只有 binder.bind(file, ProviderConfiguration.class) 一条路径
```

| 类 | 主要方法 | 职责 |
| --- | --- | --- |
| `ProviderConfiguration` | `getCapability(String)`；`credentialResolver()`；`toConfig(String name, CapabilityKind)`（★新增）；`close()` | 一个平台一个实例；`close()` 级联关闭凭据。**8 个 `toXxxConfig` 已直接删除**（不留 `@Deprecated`、不与 resolver 并存，实测清单见配套文档 §7.3，见规则四） |
| `ProviderCapabilityConfiguration` | `requireModelId()`；`requireApiProfile()`；`requireTaskQueryEndpoint()`；`requireInt(String)`；`requireBoolean(String)`；`requireString(String)` | 能力节点的类型化读取；参数只允许标量 |
| `ProviderCredentialConfiguration` | `resolver()`；`close()` | 持有密钥生命周期，`close()` 清零 |
| `ProviderTransportConfiguration` | `toHttpTransportConfig()` | 平台级传输配置 → `http.HttpTransportConfig` |
| `ProviderYamlConfigurationLoader` | `ProviderConfiguration load(Path file)` | 对外签名不变；4 张白名单 `Set` 与逐字段解析方法已删除，非字段级职责（文件大小上限、URI 安全校验、未知项拒绝）移交 `StrictYamlBinder` |

> 上表四个配置类在批次 3 已按 Q24 加 `@JsonCreator` + `@JsonProperty(value=..., required=true)`（绑定走构造器，**构造期校验全部保留**），并按 Q28 把 `@Data` 改为 `@Getter`。错误文案全部中文。

### 5.2 能力配置继承树（`configuration/config`）

```
ModelConfig (抽象)                              ★  provider / endpoint / modelId / apiProfile / secretRef
├── ChatModelConfig (抽象)                       ★  + defaultMaxTokens
│   ├── compatible.OpenAiCompatibleChatConfig    ★→  provider ∈ {OPENAI, DEEPSEEK, VOLCENGINE, ALIBABA}（四家 chat 统一由它承担，final）
│   │   └── ✗ 未创建 volcengine.VolcengineChatConfig / deepseek.DeepSeekChatConfig（原计划类，实际复用上一行）
│   └── anthropic.AnthropicChatConfig            ★→  + apiVersion、defaultMaxTokens
├── ImageModelConfig (抽象)                      ★  + taskQueryEndpoint、maxPromptChars、maxOutputs、maxSourceImages、maxInputBytes；构造器含 apiProfile
│   ├── compatible.OpenAiCompatibleImageConfig   ★→  provider ∈ {OPENAI, VOLCENGINE}（非 final，供 OPENAI 特化）
│   │   ├── openai.OpenAiImageConfig             ★  + maxInputBytes > 0（走 multipart）
│   │   └── ✗ 未创建 volcengine.VolcengineImageConfig（实际复用 OpenAiCompatibleImageConfig）
│   └── alibaba.AlibabaImageTaskConfig           ★  + outputType、outputMimeType
├── VideoModelConfig (抽象)                      ★  + taskQueryEndpoint、maxPromptChars、maxReferenceImages
│   ├── volcengine.VolcengineVideoConfig         ★→
│   └── alibaba.AlibabaVideoTaskConfig           ★
└── AudioModelConfig (抽象)                      ★  + maxTextChars、maxInputBytes、maxOutputs
    ├── openai.OpenAiAudioConfig                 ★→  由 OpenAiMediaConfig 拆分
    └── alibaba.AlibabaAudioConfig               ★→
```

> 以上为**批次 3 落地后的实际继承树**：`volcengine.VolcengineChatConfig` / `VolcengineImageConfig` 与 `alibaba.AlibabaChatConfig` 当前**未创建**，chat 与 image 分别由 `OpenAiCompatibleChatConfig` / `OpenAiCompatibleImageConfig` 承担。`ImageModelConfig` 及其派生类的构造器含 `apiProfile`（取自能力节点既有 `apiProfile` 键）。

| 类 | 主要方法 | 职责 |
| --- | --- | --- |
| `ModelConfig` | `getProvider()`；`getEndpoint()`；`getModelId()`；`getApiProfile()`；`getSecretRef()` | 能力配置公共部分；构造器校验 endpoint 绝对、modelId/secretRef 非空白 |
| `ChatModelConfig` | `getDefaultMaxTokens()` | 对话类配置公共部分 |
| `ImageModelConfig` | `getMaxPromptChars()`；`getMaxOutputs()`；`getMaxSourceImages()`；`getMaxInputBytes()`；`getTaskQueryEndpoint()` | 图片类限制；`maxInputBytes > 0` 才允许 multipart 编辑 |
| `VideoModelConfig` | `getMaxPromptChars()`；`getMaxReferenceImages()`；`getTaskQueryEndpoint()` | 视频类限制 |
| `AudioModelConfig` | `getMaxTextChars()`；`getMaxInputBytes()`；`getMaxOutputs()` | 音频类限制 |
| `compatible.OpenAiCompatibleChatConfig` | 继承 `getXxx()` + 平台白名单校验 | OpenAI 兼容对话的固定配置（provider/endpoint/modelId/apiProfile/secretRef）；**final**，OPENAI / DEEPSEEK / VOLCENGINE / ALIBABA 四家 chat 共用 |
| `compatible.OpenAiCompatibleImageConfig` | 继承 `getXxx()` | OpenAI 兼容图片的固定配置；**非 final**，OPENAI 的 `OpenAiImageConfig` 在其上特化 |
| `anthropic.AnthropicChatConfig` | `getApiVersion()`；`getDefaultMaxTokens()` | Anthropic Messages 配置 |
| `openai.OpenAiImageConfig` / `OpenAiAudioConfig` | 继承 `getXxx()` | OpenAI 图片（支持 multipart 编辑）/ 音频（转写、合成共用） |
| `volcengine.VolcengineVideoConfig` | `getXxx()`（视频含 `tasksEndpoint`） | 方舟视频配置；**chat / image 未单独建类**，分别由 `OpenAiCompatibleChatConfig` / `OpenAiCompatibleImageConfig` 承担 |
| `alibaba.AlibabaImageTaskConfig` / `AlibabaVideoTaskConfig` / `AlibabaAudioConfig` | `getXxx()`（任务类含 `taskQueryEndpoint`、`outputType`、`outputMimeType`） | 阿里云 image / video / audio 三类能力；**chat 未单独建类**，由 `OpenAiCompatibleChatConfig` 承担 |

### 5.3 配置解析（`configuration/resolver`）

```
resolver/                              （本包不含任何枚举）
├── IModelConfigResolver (接口)        ★  supports(provider, kind) / resolve(providerConfig, capabilityName, kind)
├── OpenAiCompatibleConfigResolver     ★  负责 OPENAI / DEEPSEEK / VOLCENGINE / ALIBABA 的 chat
├── OpenAiConfigResolver               ★  负责 OPENAI 的 image（multipart）与 audio
├── VolcengineConfigResolver           ★  负责 VOLCENGINE 的 image / video（不覆盖 chat）
├── AnthropicConfigResolver            ★  负责 ANTHROPIC 的 chat
├── AlibabaConfigResolver              ★  负责 ALIBABA 的 image / video / audio（不覆盖 chat）
└── ModelConfigResolvers (注册表)       ★  静态不可变列表 + forProvider(ModelProvider, CapabilityKind) 查找「平台 × 能力」
```

> **`CapabilityKind` 不在本包**：按 Q14 硬规则，它定义在 **`clara-core` 的 `com.clara.model.api`**。本节早期的子包示意图曾把它画在 `resolver/` 下，属笔误，以 Q14 与本节说明为准。
> `VolcengineConfigResolver` / `AlibabaConfigResolver` 不覆盖 chat，是为满足注册表「平台 × 能力」组合唯一的要求，避免与 `OpenAiCompatibleConfigResolver` 重复注册。

| 类 | 主要方法 | 职责 |
| --- | --- | --- |
| `CapabilityKind`（**位于 `clara-core` 的 `com.clara.model.api`**，不在本包） | 枚举常量 | 把「能力种类」从 YAML 能力名（`chat`/`image-fast`/…）中解耦；§5.3 子包图把它画在 `resolver/` 下是笔误，以 Q14 硬规则为准 |
| `IModelConfigResolver` | `boolean supports(ModelProvider, CapabilityKind)`；`ModelConfig resolve(ProviderConfiguration, String capabilityName, CapabilityKind)` | 一个平台一个实现；把 YAML 能力节点映射为具体 `*Config` |
| `ModelConfigResolvers` | `static ModelConfig resolve(ProviderConfiguration, String capabilityName, CapabilityKind)`；`static IModelConfigResolver forProvider(ModelProvider, CapabilityKind)` | 注册与查找；静态不可变列表 + **类初始化期校验「平台 × 能力」组合唯一**，不支持时抛 `CAPABILITY_NOT_SUPPORTED` |

**收益**：新增平台只加一个 resolver，`ProviderConfiguration` 不再被修改；YAML 参数键名的历史差异（`maxTextChars` / `maxPromptChars` / `maxImages` / `maxOutputs`）**已在批次 3 一次性统一，不保留任何别名**——实测改键 **8 处 / 5 个文件**（含 `maxInputImages` → `maxSourceImages` / `maxReferenceImages`；音频节点的 `maxTextChars` 因 `AudioModelConfig` 字段名本就是它而保留，不是漏改；见 §5.4 与规则四）。

### 5.4 YAML 绑定层（注解 + 严格绑定器，Q24~Q27）

```
configuration/
├── StrictYamlBinder                   ★  文件校验 + 严格读取 + 未知项拒绝 + Jackson 异常转中文
├── deserialize/
│   ├── SafeHttpUriDeserializer        ★  YAML 字符串 → 安全绝对 HTTP(S) URI（无 userinfo）
│   ├── ScalarMapDeserializer          ★  YAML 对象 → 只含字符串/数字/布尔的 Map（parameters 用）
│   └── SecretCharsDeserializer        ★  YAML 字符串 → char[]（apiKey 用；Jackson 默认按字符数组处理）
└── ProviderYamlConfigurationLoader    →  退化为门面：load(Path) 内部只调 binder.bind(file, ProviderConfiguration.class)
```

| 类 | 主要方法 | 职责 |
| --- | --- | --- |
| `StrictYamlBinder` | `StrictYamlBinder(long maxFileBytes)`；`<T> T bind(Path file, Class<T> type)` | 统一承担 loader 原来的非字段级职责：普通 `.yml/.yaml` 文件 + 非符号链接 + 体积上限、`STRICT_DUPLICATE_DETECTION`、单文档、`FAIL_ON_UNKNOWN_PROPERTIES`、枚举大小写不敏感、Jackson 异常 → 带字段路径的中文提示 |
| `SafeHttpUriDeserializer` | `URI deserialize(JsonParser, DeserializationContext)` | 保住现状的 URI 安全校验（绝对、有主机、无 userinfo、仅 HTTP(S)），从 `JsonNodeUtils.safeHttpUri` 迁入 |
| `ScalarMapDeserializer` | `Map<String,Object> deserialize(...)` | 保住现状的「扩展参数只允许标量」约束 |
| `SecretCharsDeserializer` | `char[] deserialize(...)` | 把 YAML 字符串转成 `char[]`，使内联密钥的绑定方式与现状一致（用后清零） |
| `ProviderYamlConfigurationLoader` | `ProviderConfiguration load(Path)` | 对外入口签名不变（`NovelAgentMain` 与既有测试的调用点不改），**但内部只有一条实现路径**（→ `StrictYamlBinder`），不存在「旧手工解析 + 新注解绑定」并存；4 张白名单 `Set` 与逐字段解析方法全部删除 |

配置类一侧的注解示例（形状声明与校验点合一）：

```java
public final class ProviderConfiguration {
    @JsonCreator
    public ProviderConfiguration(
            @JsonProperty(value = "provider", required = true) ModelProvider provider,
            @JsonProperty(value = "credential", required = true) ProviderCredentialConfiguration credential,
            @JsonProperty(value = "transport", required = true) ProviderTransportConfiguration transport,
            @JsonProperty(value = "capabilities", required = true)
            Map<String, ProviderCapabilityConfiguration> capabilities) { /* 现有校验原样保留 */ }
}
```

**收益**：加配置项 = 加一个字段（一处）；白名单由注解派生，不会再与实际支持漂移；嵌套节点（批次 8 的 `observability`）自动支持；**旧键不再被接受**（键名改错/漏改的文件在加载期直接报「未知配置项」，不会静默按默认值跑偏）；绑定走构造器，**构造期校验全部保留**。
**落地情况（批次 3 ✅）**：三个自定义反序列化器已就位（缺一个就会静默丢校验，这是必须盯住的点）；`ProviderYamlConfigurationLoaderTest` 里 3 处英文文案断言已同步为中文，并新增「不安全 URI 与非标量扩展参数」用例，**共 6 个用例全绿**；`config/` 下 provider YAML 的键名已同批次改齐（实测 8 处 / 5 文件，音频节点 `maxTextChars` 保留）；`JsonNodeUtils` 的配置向方法已删除、协议向 4 个方法保留（见配套文档 §7.4、§8.3、§11.5）。

---

## 6. `codec` — 协议编解码

职责：`request` ⇄ 线格式 ⇄ `response`。
**组织方式（Q2 决策后）**：兼容层是**模板**（非 final，暴露可覆盖钩子），平台差异由**派生 codec** 覆盖钩子实现——即原先三个 `options` 实现类的逻辑原样搬进各平台 codec。因此 `codec.compatible` 仍然不认识具体平台。
**✅ 现状：批次 5/6 的 chat 线与 image 线已落地**（视频 / 音频线未开工）。

```
codec/
├── compatible/                                        【模板层】（非 final，暴露可覆盖钩子）
│   ├── OpenAiCompatibleChatCodec                       →  同步编解码 + 钩子 + `adapt(ChatRequest)`
│   ├── OpenAiCompatibleChatStreamReader                ★  流式读流 + 钩子
│   ├── OpenAiCompatibleImageCodec                      →  JSON / multipart 两条编码路径 + 钩子 + `adapt` / `response`
│   ├── OpenAiCompatibleImageStreamReader               ★  流式读流 + 钩子 + `payload()`
│   ├── ChatStreamDelta                                 →  对话增量产物（原 StreamChunk）
│   ├── ToolDelta                                       →  单个工具调用本次新增的片段
│   ├── ImageStreamDelta                                ★  图片增量产物
│   ├── ImageCodecOutput                                ★→  原 codec 内部类 `Output` 摊平
│   ├── ImageCodecPayload                               ★→  原 codec 内部类 `Decoded` 摊平（响应解析结果）
│   └── OpenAiCompatibleReasoning                       →  reasoning_content 的字段名与元数据键常量
├── deepseek/DeepSeekChatCodec                          ★  原 DeepSeekChatOptions
├── volcengine/
│   ├── VolcengineChatCodec                             ★  思考开关、stream_options 细节
│   ├── VolcengineImageCodec                            ★  原 VolcengineImageOptions
│   └── VolcengineImageStreamReader                     ★
├── openai/OpenAiImageCodec                             ★  原 OpenAiImageOptions
├── alibaba/AlibabaChatCodec                            ★
└── anthropic/                                          【独立协议体系】
    ├── AnthropicChatCodec                              →  独立编解码 + `adapt(ChatRequest, AnthropicChatConfig)`
    ├── AnthropicStreamReader                           ★
    └── AnthropicStreamDelta                            ★→  原 AnthropicStreamChunk
```

### 6.1 兼容模板（`codec.compatible`）

| 类 | 主要方法 | 职责 |
| --- | --- | --- |
| `OpenAiCompatibleChatCodec`（**非 final**） | `byte[] encode(OpenAiCompatibleChatRequest, OpenAiCompatibleChatConfig, boolean stream)`；`ChatResponse decode(byte[], OpenAiCompatibleChatConfig)`；`public OpenAiCompatibleChatRequest adapt(ChatRequest)`；`public OpenAiCompatibleChatStreamReader streamReader(OpenAiCompatibleChatConfig, int maxToolCalls, int maxAccumulatedChars)`；**protected** `void applyProviderFields(OpenAiCompatibleChatRequest, Map<String,Object> body)` | 通用字段（`model`/`messages`/`tools`/采样参数）编码；`stream=true` 时写 `stream` 与 `stream_options.include_usage`；响应解析含 `finish_reason` 映射与 usage 口径。平台字段通过钩子写入，默认空实现；`adapt` 负责「基类请求 → 平台派生请求」的**唯一一处适配** |
| `OpenAiCompatibleChatStreamReader`（**非 final**） | 构造器 `(OpenAiCompatibleChatConfig, int maxToolCalls, int maxAccumulatedChars)`；`ChatStreamDelta accept(String data)`；`boolean isComplete()`；**protected** `void applyProviderChunk(JsonNode delta, ChatStreamState state)` | 消费一个 SSE 事件、更新 `ChatStreamState`、产出增量（文本/推理/工具参数）；末块产出完整 `ChatResponse` |
| `OpenAiCompatibleImageCodec`（**非 final**） | `byte[] encode(ImageRequest, OpenAiCompatibleImageConfig, ProviderMediaAssetBridge, boolean stream)`；`MultipartBody encodeMultipart(ImageRequest, config, ProviderMediaAssetBridge)`；`ImageCodecPayload decode(byte[], config, int expectedCount)`；`ImageResponse response(ImageCodecPayload, List<String> assetIds, config)`；`public OpenAiCompatibleImageRequest adapt(ImageRequest)`；`public OpenAiCompatibleImageStreamReader streamReader(config)`；`public boolean usesMultipart(ImageRequest)`（默认 false）；`public void validate(ImageRequest)`（默认空） | 两条编码路径（JSON / multipart）与响应信封解析；`data[]` 条数必须等于 `n`；**`decode` 只返回解析结果 `ImageCodecPayload`，落盘后的 `ImageResponse` 由 `response(payload, assetIds, config)` 组装**（落盘是模型职责，故不能像原设计那样 `decode → ImageResponse`） |
| `OpenAiCompatibleImageStreamReader`（**非 final**） | 构造器 `(OpenAiCompatibleImageConfig)`；`ImageStreamDelta accept(String data)`；`boolean isComplete()`；`ImageCodecPayload payload()`；**protected** `void applyProviderEvent(JsonNode root, ImageStreamState state)` | 解析 `image_generation.partial_succeeded` / `partial_failed` / `completed` 与顶层 `error` |
| `ChatStreamDelta` | `getTextDelta()`；`getReasoningDelta()`；`getToolDeltas()`；`getResponse()`；`isComplete()` | 一次 SSE 解析产出的对话增量产物（原 `StreamChunk`） |
| `ToolDelta` | `getIndex()`；`getId()`；`getName()`；`getArgumentsDelta()` | 一次 SSE 里单个工具调用新增的片段 |
| `ImageStreamDelta` | `getKind()`；`getImageIndex()`；`getOutput()`；`getError()`；`getMetadata()` | 一次 SSE 解析产出的图片增量产物，`kind` 为 `com.clara.enums.ImageStreamDeltaKind`（原 `OpenAiCompatibleImageStreamAccumulator.Chunk`） |
| `ImageCodecOutput` | `getUrl()`；`getBytes()` 等 | 一条图片输出：远程 URL 或内联字节（原 `OpenAiCompatibleImageCodec.Output` 摊平） |
| `ImageCodecPayload` | `getOutputs()`；`getRequestId()`；`getMetadata()` 等 | 一次响应解析结果：输出列表 + 平台请求标识（原 `OpenAiCompatibleImageCodec.Decoded` 摊平） |
| `OpenAiCompatibleReasoning` | 常量（字段名 / 元数据键） | `reasoning_content` 的线格式字段名与元数据键常量 |

> **与早期设计的偏差（以现状为准）**：① chat 模板的 `streamReader` 是 **public**（跨包被模型实现调用），且多带 `maxToolCalls` / `maxAccumulatedChars` 两个参数，语义上仍等同「可被平台子类覆盖的扩展点」；② 图片模板的 `usesMultipart` / `validate` 同样是 **public**（跨包调用，语义等同 protected 扩展点）；③ 入口适配（基类请求 → 平台派生请求）落在各平台 codec 的 `adapt`，由模型入口调用，`instanceof` 只出现在该处。

### 6.2 平台派生 codec

| 类 | 覆盖的钩子 | 职责（原逻辑来源） |
| --- | --- | --- |
| `deepseek.DeepSeekChatCodec`（**final**） | `adapt(ChatRequest)` / `applyProviderFields` | 把 `prefix`、`reasoning_content` 写进末条 assistant 消息；校验「prefix 开启时末条必须是 assistant」（原 `DeepSeekChatOptions`） |
| `volcengine.VolcengineChatCodec`（**final**） | `adapt(ChatRequest)` / `applyProviderFields` / 可覆盖 `streamReader` | 方舟思考开关、`stream_options` 细节 |
| `volcengine.VolcengineImageCodec`（**final**） | `adapt(ImageRequest)` / `validate` / `streamReader` / `response` | `response_format=url`、`watermark`（配置默认值 + 请求覆盖）、`seed`、`negative_prompt`、源图 URL；拒绝蒙版编辑（原 `VolcengineImageOptions`）；组装方舟派生响应 |
| `volcengine.VolcengineImageStreamReader`（**final**，extends `OpenAiCompatibleImageStreamReader`） | `applyProviderEvent` | 方舟 `usage`（`generated_images` / `output_tokens` / `tool_usage.web_search`）入状态 |
| `openai.OpenAiImageCodec`（**final**） | `adapt(ImageRequest)` / `usesMultipart` / `validate` | `response_format=b64_json`、`quality`/`style`、编辑走 multipart；拒绝 `aspectRatio`/`seed`/`negativePrompt`（原 `OpenAiImageOptions`） |
| `alibaba.AlibabaChatCodec`（**final**） | `adapt(ChatRequest)` / `applyProviderFields` | 通义 `enable_thinking` / `enable_search` |
| `anthropic.AnthropicChatCodec` | 独立体系（`adapt(ChatRequest, AnthropicChatConfig)`） | `system` 独立字段、`max_tokens` 必填、工具 schema 转换、`stop_reason` 映射 |
| `anthropic.AnthropicStreamReader` | 独立体系 | 按事件名（`message_start` / `content_block_delta` / `message_delta` / `message_stop`）分派，产出 `AnthropicStreamDelta` |

> 增量产物（`ChatStreamDelta` / `ImageStreamDelta`）是编解码输出而非状态，故归 `codec` 而非 `status`（**Q9**）。

---

## 7. `provider` — 模型实现层

唯一同时认识「平台 × 能力」的层。每个平台的能力实现都持有：平台配置 + 平台请求/响应子类 + 对应 codec + `ModelInvoker`。

### 7.1 结构

```
provider/                                        （✅ 批次 5/6 的 chat / image 已切换为「构造器注入 codec」）
├── compatible/
│   ├── OpenAiCompatibleChatModel              =  implements IChatModel（构造器注入平台 codec，Q10-a）✅
│   ├── OpenAiCompatibleStreamingChatModel      =  implements IStreamingChatModel（构造器注入 codec + 上限参数）✅
│   ├── handler/EventEmitter                    =  对话流事件发射器——**未摊平**（目标名 `ChatStreamEventEmitter`，随视频 / 音频批次一并做）
│   └── image/
│       ├── OpenAiCompatibleImageModel          =  implements IImageGenerationModel（构造器注入 codec；编码/解析后由 codec 的 `response(payload, assetIds, config)` 组装平台派生响应）✅
│       ├── OpenAiCompatibleStreamingImageModel =  implements IStreamingImageGenerationModel（同上；逐张落盘即回调，单张失败不中断）✅
│       ├── OpenAiCompatibleImageValidator      =  前置校验（**不读请求的 `providerOptions` 槽位**；平台归属由入口 `adapt` 决定）
│       └── ✗ OpenAiCompatibleImageStreamPlan / OpenAiCompatibleImageStreamAccumulator  已删除（拆为 codec 的 `ImageStreamReader` + status 的 `ImageStreamState`）
├── ✗ compatible/config/OpenAiCompatibleReasoning  已迁至 `codec.compatible.OpenAiCompatibleReasoning`
├── anthropic/
│   ├── AnthropicChatModel                     =  implements IChatModel（构造器注入 `AnthropicChatCodec`）✅
│   └── AnthropicStreamingChatModel             =  implements IStreamingChatModel（含 `maxContentBlocks` / `maxAccumulatedChars`）✅
├── deepseek/
│   └── ✗ DeepSeekChatOptions                  已删除（逻辑 → codec.deepseek.DeepSeekChatCodec）
├── openai/
│   ├── OpenAiAudioTranscriptionModel           =  implements IAudioTranscriptionModel
│   ├── OpenAiSpeechSynthesisModel              =  implements ISpeechSynthesisModel
│   ├── ✗ OpenAiImageOptions                   已删除（逻辑 → codec.openai.OpenAiImageCodec）
│   └── OpenAiMediaConfig                      →  拆入 configuration.config.openai.OpenAiAudioConfig
├── volcengine/
│   ├── VolcengineVideoJobFactory               =  创建视频异步任务操作（内部 `Operation` **未摊平**）
│   ├── ✗ VolcengineImageOptions               已删除（逻辑 → codec.volcengine.VolcengineImageCodec）
│   └── VolcengineVideoConfig                  →  configuration.config.volcengine.*
├── alibaba/
│   ├── AlibabaNativeMediaJobFactory            =  创建图片/视频异步任务操作（内部 `Operation` **未摊平**）
│   ├── AlibabaAudioTranscriptionModel          =  implements IAudioTranscriptionModel
│   ├── AlibabaSpeechSynthesisModel             =  implements ISpeechSynthesisModel
│   ├── AlibabaAudioConfig                     →  configuration.config.alibaba.*
│   └── AlibabaNativeTaskConfig                →  configuration.config.alibaba.*
└── media/                                        媒体支撑（本次不改）
    ├── ProviderMediaAssetBridge (接口)          =  媒体资产桥——**尚未改名**（目标名 `IProviderMediaAssetBridge`）
    ├── MediaJson                               =  JSON 编解码工具
    ├── MultipartBody                           =  multipart/form-data 构造器
    └── AbstractJsonMediaJobOperation           =  异步任务操作基类
```

> **`OpenAiCompatibleOptions` / `OpenAiCompatibleImageOptions` 及三个 options 实现类已删除**（Q2 结论，批次 5/6 落地）。
> 平台差异只存在于两处：`request.<platform>.*`（字段，由 codec 的 `adapt` 从基类请求适配而来）与 `codec.<platform>.*`（线格式）。
> **平台专属选项接口已删除**：类型文件、请求类槽位与 codec / 模型引用均已清理完毕（批次 6）。

### 7.2 模型实现类与方法

| 类 | 主要方法 | 职责 |
| --- | --- | --- |
| `OpenAiCompatibleChatModel` | `ChatResponse chat(ChatRequest)` | 同步对话：校验 → 编码 → 一次往返 → 解析；构造器接收平台 codec（Q10-a） |
| `OpenAiCompatibleStreamingChatModel` | `IStreamHandle chat(ChatRequest, IStreamObserver)` | 流式对话：立即返回句柄，读取线程内按序发 `TEXT_DELTA` / `REASONING_DELTA` / `TOOL_ARGUMENT_DELTA` / `TOOL_CALL_COMPLETE`，终态 `RESPONSE_COMPLETE` 或 `ERROR` |
| `compatible.image.OpenAiCompatibleImageModel` | `ImageResponse generate(ImageRequest)` | 同步生图：按 codec 的 `usesMultipart` 选择 JSON 或 multipart 路径，逐条落盘后汇总 |
| `compatible.image.OpenAiCompatibleStreamingImageModel` | `IStreamHandle generate(ImageRequest, IImageGenerationObserver)` | 流式生图：每张图落盘即回调 `IMAGE`，单张失败回调 `IMAGE_FAILED`，终态 `RESPONSE_COMPLETE` / `ERROR` |
| `AnthropicChatModel` | `ChatResponse chat(ChatRequest)` | Anthropic 同步对话 |
| `AnthropicStreamingChatModel` | `IStreamHandle chat(ChatRequest, IStreamObserver)` | Anthropic 流式对话 |
| `OpenAiAudioTranscriptionModel` | `TranscriptionResponse transcribe(AudioTranscriptionRequest)` | OpenAI 语音识别（multipart 上传） |
| `OpenAiSpeechSynthesisModel` | `SpeechResponse synthesize(SpeechSynthesisRequest)` | OpenAI 语音合成（二进制响应落盘） |
| `AlibabaAudioTranscriptionModel` | `TranscriptionResponse transcribe(AudioTranscriptionRequest)` | 阿里语音识别 |
| `AlibabaSpeechSynthesisModel` | `SpeechResponse synthesize(SpeechSynthesisRequest)` | 阿里语音合成 |
| `VolcengineVideoJobFactory` | `MediaJobProviderOperation create(VideoRequest)` | 方舟视频任务：创建提交/轮询/取消操作 |
| `AlibabaNativeMediaJobFactory` | `MediaJobProviderOperation create(ImageRequest)`；`MediaJobProviderOperation create(VideoRequest)` | 阿里原生异步任务（图片与视频） |

### 7.3 平台差异的落点（Q2 决策后：不再有 options 扩展点）

| 原先的 options 类 | 现在的位置 | 迁移方式 |
| --- | --- | --- |
| `compatible.OpenAiCompatibleOptions`（接口） | **删除** | 平台字段改由 `codec.compatible.OpenAiCompatibleChatCodec#applyProviderFields` 钩子写入 |
| `deepseek.DeepSeekChatOptions` | `codec.deepseek.DeepSeekChatCodec` | 逻辑原样搬移；请求侧改由 `request.compatible.deepseek.DeepSeekChatRequest` 携带 `prefixCompletion` |
| `compatible.image.OpenAiCompatibleImageOptions`（接口） | **删除** | 图片侧拆为三个钩子：`applyProviderFields` / `usesMultipart` / `validate` |
| `openai.OpenAiImageOptions` | `codec.openai.OpenAiImageCodec` | 逻辑原样搬移；请求侧改由 `request.image.openai.OpenAiImageRequest` |
| `volcengine.VolcengineImageOptions` | `codec.volcengine.VolcengineImageCodec` | 逻辑原样搬移；请求侧改由 `request.image.volcengine.VolcengineImageRequest` |
| 平台专属选项接口（原根接口） | **已删除** | 平台识别改由请求子类的类型 + 配置的 `provider` 共同决定（经 codec 的 `adapt`）；类型、槽位与引用均已清理（批次 6） |

因此平台差异只有两个落点：**请求子类**（字段）与**派生 codec**（线格式），模型类本身不含任何平台分支。

### 7.4 媒体支撑（本次不改）

| 类 | 主要方法 | 职责 |
| --- | --- | --- |
| `ProviderMediaAssetBridge` (接口，**目标名 `IProviderMediaAssetBridge`，尚未改名**) | `inputReference(String, MediaType, ConversationContext, CallContext)`；`inputBytes(String, MediaType, int, ConversationContext, CallContext)`；`storeRemote(String, MediaType, String, ConversationContext, CallContext)`；`remoteUpload(String, MediaType, String, ConversationContext, CallContext)`；`storeBytes(byte[], MediaType, String, ConversationContext, CallContext)` | 隔离适配器与媒体存储 / 签名 URL：把资产 ID 换成平台可访问引用、把平台结果落盘为资产 ID。租户用于存储分区、调用上下文用于取消与超时，两者**分开传** |
| `MediaJson` | `byte[] write(Object)`；`JsonNode read(byte[])`；`static String requiredText(JsonNode, String, ModelProvider)` | 与平台绑定的 JSON 工具，失败归因到平台 |
| `MultipartBody` | `MultipartBody(String boundary)`；`text(String, String)`；`file(String, String, String, byte[])`；`String contentType()`；`byte[] finish()` | 构造 multipart/form-data |
| `AbstractJsonMediaJobOperation` | `submit(String idempotencyKey)`；`poll(String providerJobId)`；`cancel(String providerJobId)`；`supportsIdempotentSubmission()`；抽象钩子 `idempotencyHeader()` / `taskId(JsonNode)` / `status(JsonNode)` / `outputUrl(JsonNode)` / `cancelEndpoint(String)`；可覆盖钩子 `submitHeaders()` / `progress(...)` / `safeErrorCode(JsonNode)` / `outputMimeType()` / `taskEndpoint(String)` | 异步媒体任务的模板实现：子类只提供平台 JSON 字段的读取与端点拼接 |

---

## 8. `runtime`（本次不动）与 `com.clara.utils`（批次 U 上移）

> `utils` 已按 Q30~Q32 整体上移到 `clara-core` 的 `com.clara.utils`：**类名与行为不变，只换包**；
> 唯一的两处调整是 `FinishReasonUtils` 去掉对 model 累加器的 Javadoc 引用、`JsonNodeUtils` 按 Q27 删掉配置向方法。
> 本节保留 utils 的方法清单，便于核对搬迁前后一致性。

### 8.1 `runtime` — 注册表与能力约束

| 类 | 主要方法 | 职责 |
| --- | --- | --- |
| `ModelRegistry` | `register(RegisteredModel)`；`RegisteredModel get(ModelProvider, String alias)` | 以 `(provider, alias)` 为键注册与查找；重复注册报配置错误；无注销/列举 |
| `RegisteredModel` | 构造器校验 + `getDescriptor()` / `getChatModel()` / `getStreamingChatModel()` | 一个已注册模型：描述符 + 可用的能力端点（Q29 移除 `embeddingModel` 字段与 `getEmbeddingModel()`，构造器由 4 参变 3 参） |
| `ProviderModelDescriptor` | `supports(ModelCapability)`；`getProvider()` / `getModelId()` / `getAlias()` / `getApiProfile()` / `getRegion()` / `getRevision()` / `getCapabilities()` / `getMetadata()` | 模型身份与能力集合 |
| `ProviderModelMetadata` | `static empty()`；`static builder()`；`Builder#build()` | 模态、上下文窗口、参数白名单、执行模式、定价版本等元数据 |
| `ModelCapabilityGuard` | `require(ProviderModelDescriptor, ModelCapability)`；`require(descriptor, ImageRequest / AudioTranscriptionRequest / SpeechSynthesisRequest / VideoRequest)` | 按请求内容推导所需能力（如 `count>1` 需 `IMAGE_BATCH_GENERATION`），不满足抛能力错误 |
| `ModelParameterOverlay` | `ModelParameters merge(ModelParameters lower, ModelParameters higher)` | 请求参数覆盖模型默认参数；`clearStops` 语义 |
| `ModelKey` / `RuntimeErrors` | 包私有 | 注册键；运行时错误工厂 |
| `ModelExecutionMode` / `ModelModality` / `ModelMetadataSource` | ✗ 已移出本包 | 执行模式 / 模态 / 元数据来源优先级；**批次 4-2a 已迁入 core 的 `com.clara.enums`**，本包不再有枚举 |

### 8.2 `utils`（★ 已移至 `clara-core` 的 `com.clara.utils`，Q30~Q32）

| 类 | 主要方法 | 职责 |
| --- | --- | --- |
| `ExceptionUtils` | `endpointDenied()`；`requestTooLarge([provider])`；`mediaTransportUnsupported(provider)`；`responseTooLarge()`；`requestTimeout()`；`protocolError([provider])`；`capabilityUnsupported()`；`transportIoError()`；`credentialUnavailable(provider)`；`providerFailure(provider, status)`；`providerEventFailure(provider, providerCode)`；`fail(...)` ×3 | 集中创建领域异常（错误码 / 类别 / 是否可重试），业务类不自行拼装 `ClaraError` |
| `JsonNodeUtils` | `textOrNull` / `strictTextOrNull` / `getIntValue` / `optionalIntValue` | 严格模式 JSON 读取（**协议向 4 个方法保留**，编解码器在用）；配置向方法（`requiredObject` / `requiredText` / `optionalText` / `requiredInt` / `optionalBoolean` / `optionalUri` / `safeHttpUri` / `rejectUnknown` / `fieldSet` / `invalidConfig`）已按 Q27 删除，`safeHttpUri` 迁入 `SafeHttpUriDeserializer` |
| `StringUtils` | `requireNotBlank` / `isBlank` / `blankToNull` / `emptyToNull` | 字符串校验与归一 |
| `MapUtils` | `unmodifiableScalarMap(Map)`；`putIfNotNull(Map, String, V)` | 标量 Map 只读化；条件写入 |
| `NumberUtils` | `Long parseDigitsOrNull(String)` | 纯数字解析（SSE `retry` 等） |
| `FinishReasonUtils` | `FinishReason of(String value)` | 平台结束原因 → 统一枚举 |

---

## 9. 能力接口一览

| 现状名 | 目标名 | 主要方法 | 目标包（Q15：契约层留 `clara-core`；**实际分包以工作区现状为准**） |
| --- | --- | --- | --- |
| `ChatModel` | `IChatModel` | `ChatResponse chat(ChatRequest)` | `com.clara.model.api` ✅ |
| `StreamingChatModel` | `IStreamingChatModel` | `IStreamHandle chat(ChatRequest, IStreamObserver)` | `com.clara.model.api` ✅ |
| `ImageGenerationModel` | `IImageGenerationModel` | `ImageResponse generate(ImageRequest)` | **`com.clara.model.api.media`** ✅ |
| `StreamingImageGenerationModel` | `IStreamingImageGenerationModel` | `IStreamHandle generate(ImageRequest, IImageGenerationObserver)` | **`com.clara.model.api.media`** ✅ |
| `AudioTranscriptionModel` | `IAudioTranscriptionModel` | `TranscriptionResponse transcribe(AudioTranscriptionRequest)` | **`com.clara.model.api.media`** ✅ |
| `SpeechSynthesisModel` | `ISpeechSynthesisModel` | `SpeechResponse synthesize(SpeechSynthesisRequest)` | **`com.clara.model.api.media`** ✅ |
| `VideoGenerationModel` | `IVideoGenerationModel` | `MediaJob submit(VideoRequest)`；`MediaJob get(String tenantId, String jobId)`；`void cancel(String tenantId, String jobId, long expectedVersion)` | **`com.clara.model.api.media`** ✅ |
| `EmbeddingModel` | ✗ **删除**（Q29：不做向量化能力） | — | — ✅ |
| `StreamObserver` | `IStreamObserver` | `void onEvent(StreamEvent)` | `com.clara.model.api` ✅ |
| `ImageGenerationObserver` | `IImageGenerationObserver` | `void onEvent(ImageGenerationEvent)` | **`com.clara.model.api.media`** ✅ |
| `StreamHandle` | `IStreamHandle` | `boolean cancel()`；`boolean isDone()` | `com.clara.model.api`，实现类为 `status.StreamHandleState` ✅ |
| `Content` | `IContent` | `String getType()` | `com.clara.model.api`（Q15 覆盖 Q11，`model.message` 包作废） ✅ |

同步 / 流式的分工：**同一个请求 DTO 不携带「是否流式」以外的语义差异**，两条通道由两个方法表达（`chat(request)` 与 `chat(request, observer)`）；HTTP 层对应 `exchange` 与 `exchangeStreaming`（**Q5** 若选择在 DTO 上加 `stream` 标志，则需明确该取舍）。

---

## 10. 关键调用链

### 10.1 非流式对话

```
调用方
 └─ IChatModel.chat(ChatRequest)（平台侧用 VolcengineChatRequest 等派生请求）
     └─ provider.compatible.OpenAiCompatibleChatModel
         ├─ codec.adapt(request) → OpenAiCompatibleChatRequest   ← 入口唯一一次「基类请求 → 平台派生请求」适配
         ├─ ModelCapabilityGuard.require(descriptor, request)
         ├─ codec.compatible.OpenAiCompatibleChatCodec.encode(request, config, false)
         │     └─ applyProviderFields(request, body)         ← 平台钩子（由平台 codec 覆盖）
         ├─ http.ModelInvoker.exchange(request, request.getConversation(), request.getCall(), ICallExchange)
         │     ├─ ICredentialResolver.resolve(request.getConversation(), secretRef)  ← finally 清零
         │     ├─ ICallSpec: endpoint / payload / method / headers
         │     ├─ http.IHttpTransport.execute(HttpRequest, request.getCall())
         │     └─ 状态码校验 → 非 2xx 抛 ExceptionUtils.providerFailure
         └─ codec.decode(body, config) → OpenAiCompatibleChatResponse
```

注：`VolcengineChatCodec` 才是实际执行的 codec 实例（模型构造时注入，Q10-a）；`adapt` 的 `instanceof` 只出现在这一处。

### 10.2 流式生图

```
调用方
 └─ IStreamingImageGenerationModel.generate(VolcengineImageRequest, observer)
     └─ provider.compatible.image.OpenAiCompatibleStreamingImageModel
         ├─ OpenAiCompatibleImageValidator.validate(config, codec, descriptor, request)
         ├─ codec.volcengine.VolcengineImageCodec.encode(request, config, assets, true)   ← stream=true
         │     └─ applyProviderFields(request, body, assets)   ← response_format=url / watermark / seed / image
         ├─ status.StreamHandleState ← 写入 executor 提交后的 Future（持 `CallContext`）
         └─（读取线程）http.ModelInvoker.exchangeStreaming(request, request.getConversation(), request.getCall(), ICallSpec, ISseListener)
             └─ http.sse.SseParser.parse(input, request.getCall(), listener)
                 └─ codec.OpenAiCompatibleImageStreamReader.accept(data)
                     ├─ partial_succeeded → 读流器写入 status.ImageStreamState（assetIds / metadata）
                     │      → ProviderMediaAssetBridge.storeRemote(...) → 回调 IMAGE
                     ├─ partial_failed    → 写入 failedIndices → 回调 IMAGE_FAILED（不中断流）
                     ├─ completed         → 回调 RESPONSE_COMPLETE（返回 false 结束读取；随后由 codec 的 `response(payload, assetIds, config)` 组装响应）
                     └─ 顶层 error        → 抛领域异常 → 回调 ERROR
```

---

## 11. 错误中文化与可观测性（独立批次 7 / 8）

> 设计依据：配套文档 §13（错误中文化）与 §14（请求日志与可观测性）；决策 Q17~Q21。
> **进度：批次 7（§11.1）✅ 已落地；批次 8（§11.2~§11.5）未开工。**

### 11.1 `com.clara.kernel.exception.ErrorMessages`（★ 批次 7）

| 方法 | 签名 | 职责 |
| --- | --- | --- |
| `of` | `static String of(String code)` | 取无变量的中文提示；未登记 code 原样返回 `code` |
| `of` | `static String of(String code, Map<String,String> args)` | 单遍插值，替换 `{provider}` / `{status}` / `{providerCode}` / `{modelId}` / `{capability}` / `{limit}` / `{actual}`；缺失占位符原样保留 |

改动落点（3 处）：

| 类 | 改动 |
| --- | --- |
| `ClaraError` | 构造器兜底：`safeMessage == null 或等于 code` → `ErrorMessages.of(code)`（不再等于 `code`） |
| `com.clara.utils.ExceptionUtils` | `providerFailure` / `credentialUnavailable` 等带上下文的工厂补变量插值（平台、平台码），变量只进消息，不重复写 `safeDetails`（批次 U 上移后路径变更，逻辑不变） |
| `com.clara.model.runtime.RuntimeErrors` | 无需改动：走 `ClaraError` 兜底即得中文 |

### 11.2 `RequestLogEntry`（★ 批次 8，core）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `stage` | `RequestLogStage` | 阶段：请求已准备 / 已发送 / 已收响应 / SSE 事件 / 已解码 / 重试排队 / 调用失败 / 调用取消 |
| `level` | `RequestLogLevel`（或 SLF4J 级别枚举） | 本条日志级别：`DEBUG` / `INFO` / `WARN` / `ERROR` |
| `message` | `String` | 中文人读文案（措辞规则同 §13.3） |
| `provider` | `String` | 平台名，可为 null |
| `method` / `endpoint` | `String` | HTTP 方法与**脱敏后**端点（仅 `scheme://host[:port]/path`） |
| `statusCode` | `Integer` | HTTP 状态码，未收到响应时为 null |
| `durationMillis` | `long` | 阶段耗时 |
| `requestBytes` / `responseBytes` | `long` | 收发字节数 |
| `traceId` | `String` | 取自 `CallContext` |
| `details` | `Map<String,String>` | 结构化补充（`attempt` / `truncated` / `contentType` / `body` 等） |

### 11.3 `RequestLogEmitter`（★ 批次 8，`clara-model` 的 `http` 包）

| 方法 | 签名 | 职责 |
| --- | --- | --- |
| 构造器 | `RequestLogEmitter(ModelProvider provider, RequestLogConfig config, IStreamObserver logObserver)` | `logObserver` 可为 null（仅走 SLF4J） |
| `isEnabled` | `boolean isEnabled()` | `config.getLevel() != OFF`；**调用方先判此方法再组装参数**，关闭时零开销 |
| `emit` | `void emit(RequestLogEntry entry)` | 若启用：按级别调 SLF4J；若 `logObserver != null` 再发 `StreamEvent(LOG, …)` |
| `elapsed` | `long elapsed(long startNanos)` | 统一耗时换算（毫秒） |

切点（**不改任何 SPI 方法签名**）：

```
http.ModelInvoker 的往返方法（exchange / exchangeStreaming / execute，批次 8 落地）
 ├─ emit(REQUEST_PREPARED)                       ← 方法 / 脱敏端点 / 平台 / traceId / 请求字节数
 ├─ IHttpTransport.execute(...)                  ← 签名不变
 ├─ emit(REQUEST_SENT)                           ← 发送耗时
 ├─ emit(RESPONSE_RECEIVED)                      ← 状态码 / 往返耗时 / 响应字节数 / 响应头名
 ├─ emit(CALL_FAILED | CALL_CANCELLED)           ← 错误码 + 中文消息（复用 ErrorMessages）
 └─（VERBOSE）emit(STREAM_EVENT)                 ← http.sse.SseParser.dispatch 内逐事件
```

### 11.4 事件契约的增量改动

| 类型 | 改动 |
| --- | --- |
| `StreamEventType` | ★ 新增取值 `LOG`（非终态，可出现在流中任意位置；终态仍是 `RESPONSE_COMPLETE` / `ERROR`） |
| `StreamEvent` | ★ 新增字段 `log: RequestLogEntry`（仅 `LOG` 事件非 null） |
| `ImageGenerationEventType` | ★ 新增取值 `LOG` |
| `ImageGenerationEvent` | ★ 新增字段 `log: RequestLogEntry` |
| `CallContext` | ★ 新增可空字段 `logObserver: IStreamObserver`（日志事件的接收方，随上下文全链路传递） |

### 11.5 带日志的调用链（与非流式对话对照）

```
调用方
 └─ CallContext.of(traceId, deadline, cancellationToken, logObserver)   ← ④ 日志接收方在此注入
     └─ IChatModel.chat(request)
         └─ http.ModelInvoker.exchange(request, call, ICallExchange)
             ├─ RequestLogEmitter(provider, effectiveLogLevel, call.getLogObserver())  ← ① 每次调用新建
             ├─ emit(REQUEST_PREPARED) → SLF4J + logObserver.onEvent(StreamEvent(LOG, …))  ← ② 双通道
             ├─ IHttpTransport.execute(...)
             ├─ emit(RESPONSE_RECEIVED)
             └─（VERBOSE）emit(STREAM_EVENT) 逐 SSE 事件
```

① 发射器**每次调用新建**（级别可能按能力不同、接收方每次不同），不做共享单例；
② `logLevel=OFF` 时既不组装 `RequestLogEntry` 也不调用 SLF4J，事件通道不产生任何 `LOG` 事件；
③ 观察者抛异常会污染主流程 —— 沿用 `EventEmitter` 现有的「吞掉观察者异常并置终态」策略；
④ 未设置 `logObserver` 时日志只走 SLF4J，事件通道静默。

---

## 12. 本文与配套文档的关系

| 主题 | 本文 | 配套文档 |
| --- | --- | --- |
| 设计原则与需求对应 | — | §1、§3~§8 |
| 类树 / 方法 / 职责 | 全部 | 字段表（§3.4、§4、§7.1） |
| 平台差异落点（兼容模板 + 派生 codec） | §6、§7.3、§10 | §3.5 |
| 命名与定义规范（`I` 前缀 / 枚举集中在 `com.clara.enums` / 禁止嵌套类型） | §0.1 | §8.2 |
| 迁移步骤 | — | §10（主线 0~6 + 独立 U / 7 / 8） |
| 已确认结论 | 顶部说明 | §11.1~§11.5 |
| 错误提示中文化 | §11.1 | §13 |
| 请求日志与可观测性 | §11.2~§11.5 | §14 |
| YAML 注解化绑定 | §5.4、§0.2 的 G 组 | §7.4、§11.5 |
| 公共工具上移（`com.clara.utils`） | §0.1 规则零、§0.2 的 A / J 组、§8.2 | §11.7、§10 批次 U |
| 不做向量化能力 | §0.1 规则一、§0.2 的 B / J 组 | §11.6（Q29） |
| 注释规范（每个重新生成的类写全） | §0.1 规则五 | §8.4、§11.8（Q33） |
