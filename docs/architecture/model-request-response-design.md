# 模型请求/响应对象类结构设计文档

> 本文档基于 [model-call-protocols.md](file:///e:/workspace_self/xxxx/clara/docs/model-call-protocols.md) 的协议字段梳理，设计一套**注解驱动**的请求/响应对象类结构：用同一套字段定义 + 字段级注解，实现「一次调用、多平台多协议复用」。
> 对应现有代码包 `com.clara.kernel.message`（已部分实现，本文档为完整的实现设计蓝图）。

---

## 1. 设计目标与原则

1. **一套字段，多端复用**：请求/响应对象不含平台、协议分支；字段上的注解声明「该字段在哪个平台、哪种协议下叫什么名字 / 从哪取 / 默认值是什么」。
2. **平台差异 → 注解数据**：`@ProviderAdapter` / `@ResponseAdapter` 承载「平台 + 能力」维度，`@ProtocolField` / `@ProtocolResponse` 承载「协议」维度，两者正交叠加。
3. **统一调用入口**：调用方只构造 `HttpRequest`，交给编码器；只接收 `HttpResponse`，由解码器按注解回填，无需感知协议。
4. **可扩展**：新增平台/协议只需在字段上加一条注解规则，或新增一个编解码器实现，不改调用方。

---

## 2. 类结构总览（树形）

```
com.clara
├── enums                              # 共享枚举（注解与字段的取值域）
│   ├── ModelProviderEnum              # 平台：OPENAI / VOLCENGINE / DEEPSEEK / ALIBABA / ANTHROPIC / KIMI
│   ├── ProtocolEnum                   # 协议：COMPLETION（Chat Completions）/ RESPONSE（Responses）
│   ├── CapabilityKindEnum             # 能力：CHAT / IMAGE / VIDEO / AUDIO_TRANSCRIPTION / SPEECH
│   ├── ThinkingEnum                   # 思考模式：enabled / disabled / auto
│   ├── RoleEnum                       # 消息角色：SYSTEM / USER / ASSISTANT / TOOL / TOOL_CALL
│   ├── FinishReasonEnum               # 结束原因：STOP / LENGTH / TOOL_CALLS / CONTENT_FILTER / CANCELLED / ERROR
│   └── StreamEventEnum                # 流事件：TEXT_DELTA / REASONING_DELTA / TOOL_ARGUMENT_DELTA / TOOL_CALL_COMPLETE / LOG / RESPONSE_COMPLETE / ERROR
│
├── annoation                          # 注解层：字段级适配规则（运行时反射读取）
│   ├── @ProviderAdapter               # 请求字段：平台 + 能力 + 线格式名 + 默认值 + 可选值 + 是否支持
│   ├── @ProviderAdapters              # 容器（可重复注解）
│   ├── @ProtocolField                 # 请求字段：协议 + 线格式名 + 是否支持
│   ├── @ProtocolFields                # 容器（可重复注解）
│   ├── @ResponseAdapter               # 响应字段：平台 + 能力 + 同步路径 + 流式路径 + 流事件 + 结束原因路径
│   ├── @ResponseAdapters              # 容器（可重复注解）
│   ├── @ProtocolResponse              # 响应字段：协议 + 同步路径 + 流式路径 + 流事件 + 结束原因路径
│   └── @ProtocolResponses             # 容器（可重复注解）
│
├── kernel
│   ├── api                            # 契约层（接口）
│   │   ├── IRequest                   # 请求根接口：getCls() / toRequest() / getStream()
│   │   ├── IResponse                  # 响应根接口：getCls() / toResponse()
│   │   ├── message/IContent           # 消息基接口：getRoleType()
│   │   └── execute/
│   │       ├── IRequestEncoder        # 请求编码器契约：encode(request, providerConfig)
│   │       └── IResponseDecoder       # 响应解码器契约：decode(raw, type) / streamAssembler(type)
│   │
│   ├── message                        # 请求参数对象层（字段 + 注解）
│   │   ├── ModelParameters            # 抽象：CHAT 文本对话通用字段
│   │   ├── ImageParameters            # 抽象：IMAGE 图像生成字段（继承 ModelParameters）
│   │   ├── delta/
│   │   │   ├── Thinking               # 思考模式值对象（thinking.type）
│   │   │   ├── UserContent            # 用户消息（role=user）
│   │   │   ├── AssistantContent       # 助手消息（role=assistant）
│   │   │   ├── SystemContent          # 系统消息（role=system）
│   │   │   ├── ToolContent            # 工具结果消息（role=tool）
│   │   │   └── ImageItem              # 图片条目（url / b64_json / size）
│   │   └── provider/                  # 编解码器（协议分派 + 反射执行）
│   │       ├── ProviderRequestEncoder # 请求编码分派入口（按协议选编码器）
│   │       ├── ProviderResponseDecoder# 响应解码分派入口（按协议选解码器）
│   │       ├── AbstractRequestEncoder # 请求编码抽象基类（反射读注解 + 序列化）
│   │       ├── AbstractResponseDecoder# 响应解码抽象基类（反射读注解 + 构建流规则）
│   │       ├── completion/
│   │       │   ├── CompletionRequestEncoder   # Chat Completions 编码
│   │       │   ├── CompletionResponseDecoder  # Chat Completions 解码
│   │       │   └── CompletionStreamAssembler  # Chat Completions 流式累加（[DONE] 终止）
│   │       └── response/
│   │           ├── ResponseRequestEncoder     # Responses 编码（字段重映射 + input 结构转换）
│   │           ├── ResponseResponseDecoder    # Responses 解码
│   │           └── ResponseStreamAssembler    # Responses 流式累加（具名事件终止）
│   │
│   ├── http                          # 实现层（具体对象）
│   │   ├── HttpRequest                # 具体请求对象（继承 ImageParameters，@SuperBuilder）
│   │   └── HttpResponse               # 统一响应对象（所有平台/协议共用）
│   └── stream/
│       └── StreamEvent                # 流事件（type + sequence + payload + toolCallId/toolName + response + error）
│
└── tool                               # 工具域（请求声明 + 响应调用）
    ├── ToolContext                    # 工具声明条目（请求侧，type + function）
    ├── ToolSpecification              # 函数签名（name + description + parameters JSON Schema）
    ├── ToolCall                       # COMPLETION 工具调用（响应侧，type=function）
    ├── ToolCallFunction               # 函数调用详情（name + arguments）
    ├── ToolCallContent                # 工具调用消息（role=assistant + tool_calls）
    ├── ResponsesToolCall              # RESPONSE 工具调用（响应侧，type=function_call）
    └── TokenUsage                     # token 用量（input/output/total/cache）
```

---

## 3. 注解设计

### 3.1 请求侧注解

#### `@ProviderAdapter` —— 平台 + 能力维度

声明「字段在哪个平台、哪种能力下如何下发」，聚焦平台差异（默认值、可选值、取值范围）。

| 属性 | 类型 | 说明 |
|------|------|------|
| `type` | `ModelProviderEnum` | 生效平台 |
| `capability` | `CapabilityKindEnum` | 生效能力（默认 `CHAT`） |
| `name` | `String` | 线格式名（可含点号嵌套，如 `response_format.type`） |
| `defaultValue` | `String` | 平台默认值（字段缺省时下发） |
| `allowed` | `String[]` | 允许的枚举取值 |
| `min` / `max` | `double` | 数值闭区间边界 |
| `maxCount` | `int` | 数组元素个数上限 |
| `supported` | `boolean` | 是否支持（`false` 表示丢弃不下发） |
| `streamingOnly` | `boolean` | 是否仅流式请求下发 |
| `note` | `String` | 额外约束说明 |

#### `@ProtocolField` —— 协议维度

声明「字段在不同协议下的线格式名」，覆盖 `@ProviderAdapter.name()`。

| 属性 | 类型 | 说明 |
|------|------|------|
| `protocol` | `ProtocolEnum` | 生效协议 |
| `name` | `String` | 协议下线格式名（空串沿用原名字） |
| `supported` | `boolean` | 协议是否支持（`false` 丢弃） |

### 3.2 响应侧注解

#### `@ResponseAdapter` —— 平台 + 能力维度

声明「响应字段在各平台响应体里的取值路径」。

| 属性 | 类型 | 说明 |
|------|------|------|
| `type` | `ModelProviderEnum[]` | 生效平台（空数组 = 全部平台通用） |
| `capability` | `CapabilityKindEnum` | 生效能力（默认 `CHAT`） |
| `path` | `String` | 同步取值路径（如 `choices[0].message.content`） |
| `streamPath` | `String` | 流式增量路径（如 `choices[0].delta.content`） |
| `event` | `StreamEventEnum` | 流式命中时发射的事件类型 |
| `terminalPath` | `String` | 结束原因路径（如 `choices[0].finish_reason`） |
| `required` | `boolean` | 同步缺失时是否按协议错误 |
| `note` | `String` | 取值口径说明 |

#### `@ProtocolResponse` —— 协议维度

声明「响应字段在不同协议下的取值路径」，覆盖 `@ResponseAdapter`。

| 属性 | 类型 | 说明 |
|------|------|------|
| `protocol` | `ProtocolEnum` | 生效协议 |
| `path` | `String` | 协议下同步路径 |
| `streamPath` | `String` | 协议下流式路径 |
| `streamEvent` | `String` | 协议下 SSE 事件名（如 `response.output_text.delta`） |
| `streamDisabled` | `boolean` | 是否禁用流式增量（改用终态一次性解析） |
| `terminalPath` | `String` | 协议下结束原因路径 |

### 3.3 容器注解

`@ProviderAdapters` / `@ProtocolFields` / `@ResponseAdapters` / `@ProtocolResponses`：对应注解的 `@Repeatable` 容器，同一字段声明多条规则。

---

## 4. 请求对象设计

### 4.1 `ModelParameters`（抽象，CHAT 文本对话字段）

继承链：`ModelParameters`（抽象）→ `ImageParameters`（抽象）→ `HttpRequest`（具体）。

| Java 字段 | 类型 | 线格式键（COMPLETION→RESPONSE） | 协议 | 平台 | 默认值 / 可选值 |
|-----------|------|-------------------------------|------|------|----------------|
| `model` | `String` | `model` | 两者 | 全部 | 必填，无默认 |
| `messages` | `List<IContent>` | `messages`→`input` | 两者 | 全部 | 必填；RESPONSE 转 Item 数组 |
| `thinking` | `Thinking` | `thinking.type`/`thinking.keep` | COMPLETION | DS/火山/Kimi | `type` 默认 `enabled` |
| `temperature` | `Double` | `temperature` | 两者 | 全部 | DS/火山默认 `1`，[0,2]；Kimi 固定不可改 |
| `topP` | `Double` | `top_p` | 两者 | 全部 | DS 默认 `1`，火山 `0.7`；Kimi 固定 `0.95` |
| `n` | `Integer` | `n` | COMPLETION | DS/火山/OA | 默认 `1`；Kimi 固定 `1` |
| `topK` | `Integer` | `top_k` | COMPLETION | ❌ 全部不支持 | — |
| `maxTokens` | `Integer` | `max_tokens`→`max_output_tokens` | 两者 | 全部 | 火山默认 `4096` |
| `maxCompletionTokens` | `Integer` | `max_completion_tokens` | COMPLETION | 火山 | [0,65536] |
| `reasoningEffort` | `String` | `reasoning_effort`→`reasoning.effort` | 两者 | DS/火山/OA/Kimi | 见协议文档 |
| `frequencyPenalty` | `Double` | `frequency_penalty` | COMPLETION | 火山/Kimi | 火山默认 `0`，[-2,2] |
| `presencePenalty` | `Double` | `presence_penalty` | COMPLETION | 火山/Kimi | 火山默认 `0`，[-2,2] |
| `responseFormat` | `String` | `response_format.type`→`text.format` | 两者 | DS/火山/Kimi | 默认 `text` |
| `stops` | `List<String>` | `stop` | 两者 | 全部 | DS ≤16，火山 ≤4，Kimi ≤5 |
| `toolChoice` | `String` | `tool_choice` | 两者 | DS/Kimi | `none/auto/required` |
| `parallelToolCalls` | `Boolean` | `parallel_tool_calls` | 两者 | 火山/Kimi | 火山默认 `true` |
| `logprobs` | `Boolean` | `logprobs` | COMPLETION | DS/火山 | 默认 `false` |
| `topLogprobs` | `Integer` | `top_logprobs` | COMPLETION | DS/火山 | 火山默认 `0`，[0,20] |
| `serviceTier` | `String` | `service_tier` | COMPLETION | 火山 | 默认 `auto` |
| `userId` | `String` | `user_id` | COMPLETION | DS | `[a-zA-Z0-9-_]`，≤512 |
| `stream` | `Boolean` | `stream` | 两者 | 全部 | 默认 `false` |
| `includeUsage` | `Boolean` | `stream_options.include_usage` | COMPLETION | 全部 | 默认 `true`（仅流式） |
| `tools` | `List<ToolContext>` | 嵌套→扁平 `function` | 两者 | 全部 | DS ≤128 |

> `store` / `previous_response_id` / `metadata` 仅 RESPONSE 协议 + OpenAI（`store` 有状态上下文），在 Responses 编码器 `postProcessBody` 中补充，不占请求对象字段。

**注解示例**（`temperature` 字段）：

```java
@ProviderAdapter(type = DEEPSEEK,   capability = CHAT, defaultValue = "1", min = 0, max = 2)
@ProviderAdapter(type = VOLCENGINE, capability = CHAT, defaultValue = "1", min = 0, max = 2)
private Double temperature;
```

**协议 + 平台双维度叠加示例**（`maxTokens`）：

```java
@JsonProperty("max_tokens")
@ProviderAdapter(type = VOLCENGINE, capability = CHAT, defaultValue = "4096", min = 1)
@ProtocolField(protocol = RESPONSE, name = "max_output_tokens")
private Integer maxTokens;
```

### 4.2 `ImageParameters`（抽象，IMAGE 图像生成字段）

| Java 字段 | 类型 | 线格式键 | 协议 | 平台 | 默认值 / 可选值 |
|-----------|------|----------|------|------|----------------|
| `prompt` | `String` | `prompt` | IMAGE | 火山 | 必选 |
| `image` | `List<String>` | `image` | IMAGE | 火山 | ≤14 张 |
| `size` | `String` | `size` | IMAGE | 火山 | 默认 `2048x2048` |
| `seed` | `Integer` | `seed` | IMAGE | 火山 | 默认 `-1`，[-1,2147483647] |
| `watermark` | `Boolean` | `watermark` | IMAGE | 火山 | 默认 `true` |
| `guidanceScale` | `Double` | `guidance_scale` | IMAGE | 火山 | [1,10] |
| `sequentialImageGeneration` | `String` | `sequential_image_generation` | IMAGE | 火山 | `auto/disabled` |
| `maxImages` | `Integer` | `sequential_image_generation_options.max_images` | IMAGE | 火山 | [1,15]，默认 `15` |
| `outputFormat` | `String` | `output_format` | IMAGE | 火山 | 默认 `jpeg` |
| `optimizePromptMode` | `String` | `optimize_prompt_options.mode` | IMAGE | 火山 | `standard/fast` |
| `responseFormat` | `String` | `response_format` | IMAGE | 火山 | 默认 `url`（继承字段，能力维度区分） |
| `stream` | `Boolean` | `stream` | IMAGE | 火山 | 默认 `false`（继承字段） |
| `tools` | `List<ToolContext>` | `tools` | IMAGE | 火山 | `{"type":"web_search"}`（继承字段） |

### 4.3 消息内容（`IContent` 实现）

| 类 | 线格式 | 用途 |
|----|--------|------|
| `SystemContent` | `{"role":"system","content":"..."}` | 系统指令 |
| `UserContent` | `{"role":"user","content":"..."}` | 用户输入 |
| `AssistantContent` | `{"role":"assistant","content":"..."}` | 助手历史回复 |
| `ToolContent` | `{"role":"tool","tool_call_id":"...","content":"..."}` | 工具执行结果 |
| `ToolCallContent` | `{"role":"assistant","content":null,"tool_calls":[...]}` | 助手要求调工具（位于 `tool` 包） |

### 4.4 工具声明（`ToolContext`）

| 类 | 字段 | 线格式 |
|----|------|--------|
| `ToolContext` | `type`（默认 `function`）+ `function` | `{"type":"function","function":{...}}`（Chat）/ `{"type":"function","name",...}`（Responses） |
| `ToolSpecification` | `name` + `description` + `parameters` | `{"name","description","parameters"}` |

---

## 5. 响应对象设计

### 5.1 `HttpResponse`（统一响应，所有平台/协议共用）

| Java 字段 | 类型 | 取值路径（COMPLETION / RESPONSE） | 协议 | 平台 | 可选值 |
|-----------|------|----------------------------------|------|------|--------|
| `raw` | `RawHttpResponse` | —（原始响应） | 两者 | 全部 | — |
| `id` | `String` | `id` | 两者 | 全部 | — |
| `model` | `String` | `model` | 两者 | 全部 | — |
| `content` | `String` | `choices[0].message.content` / `output[type=message].content[0].text` | 两者 | 全部 | — |
| `reasoning` | `String` | `choices[0].message.reasoning_content` / `output[type=reasoning].content[0].text` | 两者 | DS/Kimi（COMPLETION） | — |
| `toolCalls` | `List<ToolCall>` | `choices[0].message.tool_calls` | COMPLETION | 全部 | `type=function` |
| `responsesToolCalls` | `List<ResponsesToolCall>` | `output[]` 过滤 `function_call` | RESPONSE | 全部 | `type=function_call` |
| `finishReason` | `FinishReasonEnum` | `choices[0].finish_reason` / `status` | 两者 | 全部 | `STOP/LENGTH/TOOL_CALLS/CONTENT_FILTER/ERROR` |
| `inputTokens` | `Integer` | `usage.prompt_tokens` / `usage.input_tokens` | 两者 | 全部 | — |
| `outputTokens` | `Integer` | `usage.completion_tokens` / `usage.output_tokens` | 两者 | 全部 | — |
| `totalTokens` | `Integer` | `usage.total_tokens` | 两者 | 全部 | — |
| `cachedTokens` | `Integer` | DS `usage.prompt_cache_hit_tokens`；火山/Kimi `usage.prompt_tokens_details.cached_tokens`；RESPONSE `usage.input_tokens_details.cached_tokens` | 两者 | 全部 | — |
| `images` | `List<ImageItem>` | `data[]` | IMAGE | 火山 | — |
| `generatedImages` | `Integer` | `usage.generated_images` | IMAGE | 火山 | — |
| `webSearchCount` | `Integer` | `usage.tool_usage.web_search` | IMAGE | 火山 | — |

**注解示例**（`content` 字段，平台 + 协议双维度）：

```java
@ResponseAdapter(path = "choices[0].message.content", streamPath = "choices[0].delta.content", event = TEXT_DELTA)
@ProtocolResponse(protocol = RESPONSE, path = "output[type=message].content[0].text", streamPath = "delta", streamEvent = "response.output_text.delta")
private String content;
```

**缓存 token 平台差异示例**（`cachedTokens`，同字段多条平台规则）：

```java
@ResponseAdapter(type = DEEPSEEK, path = "usage.prompt_cache_hit_tokens")
@ResponseAdapter(type = {VOLCENGINE, OPENAI, ALIBABA}, path = "usage.prompt_tokens_details.cached_tokens")
@ProtocolResponse(protocol = RESPONSE, path = "usage.input_tokens_details.cached_tokens")
private Integer cachedTokens;
```

### 5.2 工具调用（区分协议）

| 类 | 协议 | 结构 |
|----|------|------|
| `ToolCall` + `ToolCallFunction` | COMPLETION | `{"id","type":"function","function":{"name","arguments"}}` |
| `ResponsesToolCall` | RESPONSE | `{"type":"function_call","call_id","name","arguments"}` |

> 两个独立类型避免协议结构差异互相污染，字段名（`call_id` vs `id`）可区分模式。

### 5.3 其他响应值对象

| 类 | 字段 | 说明 |
|----|------|------|
| `ImageItem` | `url` / `b64Json` / `size` | 图片条目（url 与 b64_json 二选一） |
| `TokenUsage` | `inputTokens`/`outputTokens`/`totalTokens`/`cacheTokens` | 统一 token 用量 |

---

## 6. 请求构建（编码流程）

```
HttpRequest.toRequest()
    └─ ProviderRequestEncoder.encode(request, providerConfig)
         │  1. 取当前能力 → 判断协议（config.getProtocol()）
         │  2. 分派编码器：RESPONSE → ResponseRequestEncoder，否则 CompletionRequestEncoder
         └─ AbstractRequestEncoder.encode()
              │  a. 遍历继承链字段（父类在前、子类在后）
              │  b. 匹配 @ProviderAdapter（type + capability 命中）
              │  c. 过滤：supported=false 丢弃；streamingOnly 且非流式丢弃
              │  d. 确定线格式名：@ProtocolField 覆盖 > @ProviderAdapter.name > @JsonProperty
              │  e. 值转换：RESPONSE 下 messages→input、tools 扁平化（ResponseInputConverter）
              │  f. 序列化（嵌套点号 → 嵌套对象）
              └─ g. postProcessBody：RESPONSE + OPENAI 注入 Codex 字段（client_metadata/store/include/text）
```

**关键点**：
- 平台/能力来自线程上下文 `ConvContext`，不写在请求对象里 → 换平台不换类型。
- 协议由 `ProtocolEnum` 决定，协议差异只影响「字段名」与「值结构」，不影响字段集合。

---

## 7. 响应解析（同步 + 流式）

### 7.1 同步解码

```
ProviderResponseDecoder.decode(raw, HttpResponse.class)
    └─ 按协议分派 CompletionResponseDecoder / ResponseResponseDecoder
         └─ AbstractResponseDecoder.decode()
              │  a. 解析 JSON 根节点
              │  b. 遍历响应字段，匹配 @ResponseAdapter（平台 + 能力）
              │  c. 协议覆盖：@ProtocolResponse 覆盖 path
              │  d. 按 path 取值 → 转换（Responses 工具调用过滤 function_call）
              │  e. required=true 且缺失 → 抛协议错误
              └─ 回填到 HttpResponse
```

### 7.2 流式解码

```
ProviderResponseDecoder.streamAssembler(HttpResponse.class)
    └─ 按协议分派 CompletionStreamAssembler / ResponseStreamAssembler
         │  构建三类规则：deltas（streamPath）/ singles（path）/ terminals（terminalPath）
         └─ accept(event, data) 逐条喂入 SSE：
              │  a. 终止判定：COMPLETION=[DONE]；RESPONSE=response.completed/incomplete/failed
              │  b. singles/terminals 每块按路径取一次
              │  c. deltas 命中 → 文本增量累加 / 非文本按协议发射事件
              └─ 终态 → 发射 RESPONSE_COMPLETE 或 ERROR
```

**协议差异点**：

| 维度 | COMPLETION | RESPONSE |
|------|-----------|----------|
| 终止标记 | `data: [DONE]` | `response.completed` / `incomplete` / `failed` |
| 正文增量 | `choices[0].delta.content` | `response.output_text.delta` 的 `delta` |
| 思维链增量 | `choices[0].delta.reasoning_content` | `response.reasoning_text.delta` 的 `delta` |
| 工具调用 | `choices[0].delta.tool_calls` 分片 | `response.completed` 一次性 `output[]` |
| 结束原因 | `choices[0].finish_reason` | `status`（`completed→STOP`、`incomplete→LENGTH`、`failed→ERROR`） |

---

## 8. 扩展方式

- **新增平台**：`ModelProviderEnum` 加值 + 配置 YAML + 字段上加对应 `@ProviderAdapter` / `@ResponseAdapter` 规则。
- **新增协议**：`ProtocolEnum` 加值 + 新增 `XxxRequestEncoder` / `XxxResponseDecoder` / `XxxStreamAssembler` + 字段上加 `@ProtocolField` / `@ProtocolResponse`。
- **新增能力**：`CapabilityKindEnum` 加值 + 新增 `XxxParameters` 参数类（继承 `ModelParameters`）+ 字段按 `capability` 标注。
