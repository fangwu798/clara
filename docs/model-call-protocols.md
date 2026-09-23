# 大模型调用协议梳理（DeepSeek / 火山方舟 / OpenAI / Kimi）

> 本文基于各平台**官方文档**与项目代码整理，覆盖 DeepSeek、火山方舟、OpenAI、Kimi 四家平台。
> 结构：①协议支持情况 → ②请求字段大表 → ③响应字段（按协议分开）。
> 字段的「默认值 / 可选值」列标出平台默认值与取值范围。

---

## 1. 协议支持情况

### 1.1 平台 × 协议支持矩阵

| 平台 | COMPLETION（Chat Completions） | RESPONSE（Responses） |
|------|:---:|:---:|
| OpenAI | ✅ 支持 | ✅ 支持（官方推荐新项目） |
| DeepSeek | ✅ 支持 | ✅ 支持（无状态） |
| 火山方舟 | ✅ 支持 | ✅ 支持（250615 后模型） |
| Kimi（月之暗面） | ✅ 支持 | ✅ 支持 |

> 四家平台均同时支持 COMPLETION 与 RESPONSE 两种协议。Kimi 额外提供 Anthropic 兼容的 Messages API（不在本文档范围）。

### 1.2 协议差异概览

| 维度 | COMPLETION（Chat Completions） | RESPONSE（Responses） |
|------|-------------------------------|----------------------|
| 端点 | `/v1/chat/completions` | `/v1/responses` |
| 输入 | `messages[]`（role + content） | `input`（字符串或 Item 数组） |
| 系统提示词 | `messages[role=system]` | `instructions`（顶层独立字段） |
| 输出 | `choices[].message` | `output[]`（message/reasoning/function_call） |
| 工具声明 | 嵌套 `{"type":"function","function":{...}}` | 扁平 `{"type":"function","name",...}` |
| 流式终止 | `data: [DONE]` | `response.completed` 等具名事件 |
| 状态管理 | 客户端维护全量历史 | OpenAI 支持 `store` 服务端状态（DeepSeek 无状态） |

### 1.3 平台对照表

| 平台 | 枚举 | 鉴权 | Chat Completions 端点 | Responses 端点 |
|------|------|------|----------------------|----------------|
| OpenAI | `OPENAI` | `Authorization: Bearer <key>` | `.../v1/chat/completions` | `https://new.aicontrol.top/v1/responses` |
| DeepSeek | `DEEPSEEK` | `Authorization: Bearer <key>` | `https://api.deepseek.com/chat/completions` | `https://api.deepseek.com/responses` |
| 火山方舟 | `VOLCENGINE` | `Authorization: Bearer <key>` | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` | `https://ark.cn-beijing.volces.com/api/v3/responses` |
| Kimi | `KIMI` | `Authorization: Bearer <key>` | `https://api.moonshot.cn/v1/chat/completions` | `https://api.moonshot.cn/v1/responses` |

> ⚠️ **特殊说明（非标准 OpenAI 协议）**：本项目 OpenAI 配置走的是中转站 `new.aicontrol.top`，除标准的 `Authorization: Bearer <key>` 外，中转站额外要求 `x-Transfer: true` 与 Codex 模拟请求头（`x-codex-window-id`、`x-codex-turn-metadata`、`session-id`、`thread-id`、`originator: codex-tui` 等），用于模拟授权。这些是**中转站特有**，不是标准 OpenAI 协议；直连 OpenAI 官方只需 `Authorization: Bearer <key>`。

**官方文档出处**：

- DeepSeek：[Chat Completions](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/)、[Responses](https://api-docs.deepseek.com/zh-cn/api/create-response/)
- 火山方舟：[Chat API](https://www.volcengine.com/docs/82379/1399009)、[Responses API](https://www.volcengine.com/docs/82379/1585128)
- OpenAI：[Chat Completions](https://platform.openai.com/docs/api-reference/chat)、[Responses](https://platform.openai.com/docs/api-reference/responses)
- Kimi：[Chat Completions](https://platform.moonshot.cn/docs/api/chat)、[Responses](https://platform.moonshot.cn/docs/api/responses)

---

## 2. 请求字段大表

> 图例：
> - **协议**：`COMPLETION` 仅 Chat Completions 下发；`RESPONSE` 仅 Responses 下发；`两者` 两种协议均下发（线格式键可能不同）；`IMAGE` 火山图片生成端点（`/images/generations`）。
> - **模型类型**：`文本`（CHAT 对话）、`图像`（IMAGE 生成）。视频/音频（VIDEO/AUDIO_TRANSCRIPTION/SPEECH）当前四家平台未配置，仅阿里 DashScope 原生支持，不在本文档范围。
> - **平台**：DeepSeek = DS，火山方舟 = 火山，OpenAI = OA，Kimi = Kimi。
> - **默认值 / 可选值**：`默认 X` 为平台默认；`可选 a/b/c` 为枚举取值；`[min,max]` 为数值闭区间；`≤N` 为元素个数上限；`—` 表示无默认或纯文本无约束。

### 2.1 文本对话（CHAT）

| 字段 | 协议 | 平台 | 模型类型 | 线格式键（COMPLETION → RESPONSE） | 默认值 / 可选值 |
|------|------|------|:---:|----------------------------------|----------------|
| `model` | 两者 | 全部 | 文本 | `model` → `model` | 无默认，必填；DS `deepseek-flash`/`deepseek-v4-pro`，OA `gpt-5.6-sol`，Kimi `kimi-k2.6`/`kimi-k3` |
| `messages` | 两者 | 全部 | 文本 | `messages` → `input` | 无默认，必填；RESPONSE 转为 Item 数组 |
| 系统提示词 | 两者 | 全部 | 文本 | `messages[role=system]` → `instructions` | 无默认，可选 |
| `temperature` | 两者 | 全部 | 文本 | `temperature` | DS/火山默认 `1`，[0,2]；Kimi 固定不可改（k3/k2.7 `1.0`，k2.6 思考 `1.0`/非思考 `0.6`） |
| `top_p` | 两者 | 全部 | 文本 | `top_p` | DS 默认 `1`，火山默认 `0.7`，[0,1]；Kimi 固定 `0.95` 不可改 |
| `n` | COMPLETION | DS/火山/OA | 文本 | `n` | 默认 `1`；Kimi 固定 `1` 不可改；RESPONSE 已移除 |
| `top_k` | COMPLETION | ❌ 全部不支持 | 文本 | `top_k` | — |
| `max_tokens` | 两者 | 全部 | 文本 | `max_tokens` → `max_output_tokens` | 火山默认 `4096`，[1,∞)；DS/OA/Kimi 无本地默认（随模型） |
| `max_completion_tokens` | COMPLETION | 火山 | 文本 | `max_completion_tokens` | [0,65536]；与 `max_tokens` 互斥 |
| `reasoning_effort` | 两者 | DS/火山/OA/Kimi | 文本 | `reasoning_effort` → `reasoning.effort` | DS 默认 `high`，可选 `high/max`；火山默认 `medium`，可选 `minimal/low/medium/high`；Kimi K3 默认 `max`，可选 `low/high/max`；OA 默认 `medium` |
| `thinking` | COMPLETION | DS/火山/Kimi | 文本 | `thinking.type` / `thinking.keep` | `type`：DS 默认 `enabled`（`enabled/disabled`）；火山默认 `enabled`（`enabled/disabled/auto`）；Kimi 默认 `enabled`（`enabled/disabled`）。`keep`：Kimi K2.6 `null/all`，K2.7 恒 `all` |
| `frequency_penalty` | COMPLETION | 火山/Kimi | 文本 | `frequency_penalty` | 火山默认 `0`，[-2,2]；Kimi 固定 `0` 不可改；DS 已废弃 |
| `presence_penalty` | COMPLETION | 火山/Kimi | 文本 | `presence_penalty` | 火山默认 `0`，[-2,2]；Kimi 固定 `0` 不可改；DS 已废弃 |
| `response_format` | 两者 | DS/火山/Kimi | 文本 | `response_format.type` → `text.format` | 默认 `text`；DS 可选 `text/json_object`；火山可选 `text/json_object/json_schema` |
| `stop` | 两者 | 全部 | 文本 | `stop` | 无默认；DS ≤16，火山 ≤4，Kimi ≤5（每条≤32字节） |
| `tool_choice` | 两者 | DS/Kimi | 文本 | `tool_choice` | DS 可选 `none/auto/required`；Kimi k3 支持 `auto/none/required`，k2.6/k2.7-code 不支持 `required` |
| `parallel_tool_calls` | 两者 | 火山/Kimi | 文本 | `parallel_tool_calls` | 火山默认 `true`；DS 不支持 |
| `logprobs` | COMPLETION | DS/火山 | 文本 | `logprobs` | 默认 `false`；思考模式不支持 |
| `top_logprobs` | COMPLETION | DS/火山 | 文本 | `top_logprobs` | 火山默认 `0`，[0,20]；仅 `logprobs=true` 时有效 |
| `service_tier` | COMPLETION | 火山 | 文本 | `service_tier` | 默认 `auto`，可选 `auto/default` |
| `user_id` | COMPLETION | DS | 文本 | `user_id` | 无默认；取值 `[a-zA-Z0-9-_]`，≤512 |
| `stream` | 两者 | 全部 | 文本 | `stream` | 默认 `false` |
| `stream_options` | COMPLETION | 全部 | 文本 | `stream_options.include_usage` | 默认 `true`（仅 `stream=true` 时下发）；RESPONSE 不支持 |
| `tools` | 两者 | 全部 | 文本 | 嵌套 `function{}` → 扁平 `function` | 无默认；DS ≤128；RESPONSE 默认 `strict` |
| `store` | RESPONSE | OA | 文本 | — → `store` | 默认随 OpenAI 存储策略；`true/false`（DeepSeek 无状态） |
| `previous_response_id` | RESPONSE | OA | 文本 | — → `previous_response_id` | 无默认，可选 |
| `metadata` | RESPONSE | OA/Kimi | 文本 | — → `metadata` | 无默认，可选 |

### 2.2 图像生成（IMAGE，火山方舟）

| 字段 | 协议 | 平台 | 模型类型 | 线格式键 | 默认值 / 可选值 |
|------|------|------|:---:|----------|----------------|
| `prompt` | IMAGE | 火山 | 图像 | `prompt` | 必选；中文 ≤300 字 / 英文 ≤600 词 |
| `image` | IMAGE | 火山 | 图像 | `image` | 无默认，可选；≤14 张（5.0 pro ≤10） |
| `size` | IMAGE | 火山 | 图像 | `size` | 默认 `2048x2048`；可选档位 `1K/2K/4K` 或 `宽x高`（总像素 [921600,16777216]，宽高比 [1/16,16]） |
| `seed` | IMAGE | 火山 | 图像 | `seed` | 默认 `-1`，[-1,2147483647] |
| `watermark` | IMAGE | 火山 | 图像 | `watermark` | 默认 `true`（加水印） |
| `guidance_scale` | IMAGE | 火山 | 图像 | `guidance_scale` | [1,10]；仅 3.0 系列（默认 2.5/5.5） |
| `sequential_image_generation` | IMAGE | 火山 | 图像 | `sequential_image_generation` | 默认 `disabled`（平台默认）；可选 `auto/disabled`；仅 4.5/4.0 |
| `sequential_image_generation_options.max_images` | IMAGE | 火山 | 图像 | `sequential_image_generation_options.max_images` | [1,15]，默认 `15`；仅 `auto` 时生效 |
| `output_format` | IMAGE | 火山 | 图像 | `output_format` | 默认 `jpeg`，可选 `jpeg/png`；仅 5.0 pro/lite |
| `optimize_prompt_options.mode` | IMAGE | 火山 | 图像 | `optimize_prompt_options.mode` | 可选 `standard/fast`；平台默认 `standard` |
| `response_format` | IMAGE | 火山 | 图像 | `response_format` | 默认 `url`，可选 `url/b64_json` |
| `stream` | IMAGE | 火山 | 图像 | `stream` | 默认 `false`；仅 4.5/4.0 支持流式 |
| `tools` | IMAGE | 火山 | 图像 | `tools` | 可选 `{"type":"web_search"}`（联网搜索）；仅 5.0 lite |

---

## 3. 响应字段（按协议分开）

> 图例同上。COMPLETION 与 RESPONSE 响应结构差异大，故分开整理。
> 「默认值 / 可选值」列：响应字段多为平台回填值，仅枚举型字段标可选值；`—` 表示平台动态回填、无固定枚举。

### 3.1 COMPLETION 响应字段

响应对象 `object = "chat.completion"`，正文在 `choices[]` 下。

| 字段 | 协议 | 平台 | 模型类型 | 取值路径 | 默认值 / 可选值 |
|------|------|------|:---:|----------|----------------|
| `id` | COMPLETION | 全部 | 文本 | `id` | —（平台回填，如 `chatcmpl-xxx`） |
| `object` | COMPLETION | 全部 | 文本 | `object` | 恒为 `chat.completion` |
| `created` | COMPLETION | 全部 | 文本 | `created` | —（Unix 秒时间戳） |
| `model` | COMPLETION | 全部 | 文本 | `model` | —（回显模型 ID） |
| `content` | COMPLETION | 全部 | 文本 | `choices[0].message.content` | — |
| `reasoning` | COMPLETION | DS/Kimi | 文本 | `choices[0].message.reasoning_content` | —（思考模式才有） |
| `toolCalls` | COMPLETION | 全部 | 文本 | `choices[0].message.tool_calls[]` | `type` 恒为 `function`；`{id,function:{name,arguments}}` |
| `finishReason` | COMPLETION | 全部 | 文本 | `choices[0].finish_reason` | 可选 `stop/length/tool_calls/function_call/content_filter` |
| `inputTokens` | COMPLETION | 全部 | 文本 | `usage.prompt_tokens` | — |
| `outputTokens` | COMPLETION | 全部 | 文本 | `usage.completion_tokens` | — |
| `totalTokens` | COMPLETION | 全部 | 文本 | `usage.total_tokens` | — |
| `cachedTokens` | COMPLETION | DS | 文本 | `usage.prompt_cache_hit_tokens` | ⚠️ DS 专用路径 |
| `cachedTokens` | COMPLETION | 火山/Kimi | 文本 | `usage.prompt_tokens_details.cached_tokens` | ⚠️ 火山/Kimi 路径 |
| `images` | IMAGE | 火山 | 图像 | `data[]` | —（生成图片列表） |
| `generatedImages` | IMAGE | 火山 | 图像 | `usage.generated_images` | —（生成张数） |
| `webSearchCount` | IMAGE | 火山 | 图像 | `usage.tool_usage.web_search` | —（0 表示未搜索） |

**流式增量取值路径**（`data:` 分片，终止 `[DONE]`）：

| 增量 | 取值路径 |
|------|----------|
| 正文增量 | `choices[0].delta.content` |
| 思维链增量 | `choices[0].delta.reasoning_content` |
| 工具参数增量 | `choices[0].delta.tool_calls` |

### 3.2 RESPONSE 响应字段

响应对象 `object = "response"`，正文在 `output[]` 下（按 `type` 区分）。

| 字段 | 协议 | 平台 | 模型类型 | 取值路径 | 默认值 / 可选值 |
|------|------|------|:---:|----------|----------------|
| `id` | RESPONSE | 全部 | 文本 | `id` | —（平台回填，如 `resp_xxx`） |
| `object` | RESPONSE | 全部 | 文本 | `object` | 恒为 `response` |
| `created_at` | RESPONSE | 全部 | 文本 | `created_at` | —（Unix 秒时间戳） |
| `model` | RESPONSE | 全部 | 文本 | `model` | —（回显模型 ID） |
| `status` | RESPONSE | 全部 | 文本 | `status` | 可选 `completed/incomplete/failed` |
| `content` | RESPONSE | 全部 | 文本 | `output[type=message].content[0].text` | — |
| `reasoning` | RESPONSE | 全部 | 文本 | `output[type=reasoning].content[0].text` | — |
| `toolCalls` | RESPONSE | 全部 | 文本 | `output[]` 过滤 `function_call` | `type` 恒为 `function_call`；`{call_id,name,arguments}` |
| `error` | RESPONSE | 全部 | 文本 | `error.code` / `error.message` | `code` 可选如 `gateway_concurrency_limit` 等；仅 `status=failed` |
| `incompleteReason` | RESPONSE | 全部 | 文本 | `incomplete_details.reason` | 可选如 `max_output_tokens`；仅 `status=incomplete` |
| `inputTokens` | RESPONSE | 全部 | 文本 | `usage.input_tokens` | — |
| `outputTokens` | RESPONSE | 全部 | 文本 | `usage.output_tokens` | — |
| `totalTokens` | RESPONSE | 全部 | 文本 | `usage.total_tokens` | — |
| `cachedTokens` | RESPONSE | 全部 | 文本 | `usage.input_tokens_details.cached_tokens` | — |

> `output[]` 元素 `type` 可选值：`message`（正文）、`reasoning`（思维链）、`function_call`（工具调用）、`function_call_output`（工具结果，多轮回传）。

**流式增量事件**（具名 `event:` 行，终止 `response.completed/incomplete/failed`）：

| SSE 事件名 | 取值路径 | 领域事件 |
|------------|----------|----------|
| `response.output_text.delta` | `delta` | `TEXT_DELTA` |
| `response.reasoning_text.delta` | `delta` | `REASONING_DELTA` |
| `response.completed` | `response.output`（含 `function_call`） | `TOOL_ARGUMENT_DELTA` + 终态 |
| `response.failed` | `response.error` | `ERROR` 终态 |

---

## 4. 结束原因映射

| COMPLETION `finish_reason` | RESPONSE `status` | 领域结束原因 |
|---------------------------|-------------------|--------------|
| `stop` | `completed` | `STOP` |
| `length` | `incomplete` | `LENGTH` |
| `tool_calls` / `function_call` | — | `TOOL_CALLS` |
| `content_filter` | — | `CONTENT_FILTER` |
| — | `failed` | `ERROR` |
| 其他 / 缺失 | 其他 / 缺失 | `ERROR` |
