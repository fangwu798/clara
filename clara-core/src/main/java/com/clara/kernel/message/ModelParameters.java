package com.clara.kernel.message;

import com.clara.annoation.ProviderAdapter;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.api.IRequest;
import com.clara.tool.ToolContext;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * 定义模型的参数。
 *
 * <p>字段的取值、默认值与限制以 DeepSeek（{@code https://api-docs.deepseek.com/api/create-chat-completion}）
 * 与火山方舟（{@code https://www.volcengine.com/docs/82379/1494384}）的对话接口为基准，平台差异用
 * {@link ProviderAdapter} 声明：{@code type} 是平台，{@code capability} 是使用类型（对话参数为
 * 图片生成专有参数见 {@link ImageParameters}），{@code defaultValue} 是平台默认值，
 * {@code supported = false} 表示该平台不支持、下发前必须丢弃，{@code allowed}/{@code min}/{@code max}/
 * {@code maxCount}/{@code note} 承载取值集合、数值区间、元素个数上限与前提条件。
 */
@Data
@SuperBuilder
public abstract class ModelParameters implements IRequest {
    /**
     * 使用的模型的 ID
     */
    private String model;

    /**
     * 对话的消息列表
     */
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT)
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT)
    private List<IContent> messages;
    /**
     * 控制思考模式与非思考模式的转换。
     *
     * <p>线格式为 {@code "thinking":{"type":"..."}}：DeepSeek 只接受 {@code enabled}/{@code disabled}
     * （默认 {@code enabled}）；火山方舟额外接受 {@code auto}（由模型自行判断是否思考，默认 {@code enabled}）。
     * 把 {@code auto} 下发给 DeepSeek 会被平台拒绝。
     */
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, defaultValue = "enabled", allowed = {"enabled", "disabled"}, note = "不支持 auto")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "enabled", allowed = {"enabled", "disabled", "auto"})
    private Thinking thinking;

    /**
     * 采样温度，默认 1，取值范围 [0, 2]（两家一致）。
     * 通常建议只调整 temperature 或 topP 其中之一，不要同时调整。
     */
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, defaultValue = "1", min = 0d, max = 2d)
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "1", min = 0d, max = 2d)
    private Double temperature;

    /**
     * 核采样阈值：只考虑累计概率质量在 topP 内的 token，取值范围 [0, 1]。
     *
     * <p>平台默认值不同：DeepSeek 默认 1，火山方舟默认 0.7。
     */
    @JsonProperty("top_p")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, defaultValue = "1", min = 0d, max = 1d)
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "0.7", min = 0d, max = 1d)
    private Double topP;

    /**
     * 只从概率最高的 topK 个 token 中采样。
     *
     * <p>DeepSeek 与火山方舟的对话接口均<b>不支持</b> {@code top_k}（兼容协议下已由 codec 直接拒绝），
     * 该字段仅在 Anthropic 等平台上有效。
     */
    @JsonProperty("top_k")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, supported = false)
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, supported = false)
    private Integer topK;

    /**
     * 限制一次请求中模型生成 completion 的最大 token 数，取值必须为正。
     *
     * <p>默认值平台差异：火山方舟默认 4096；DeepSeek 默认值随模型与思考模式变化，故不在本地兜默认值。
     * 输出 token 与输入 token 的总长仍受模型上下文长度限制。
     */
    @JsonProperty("max_tokens")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, min = 1d, note = "默认值随模型与思考模式变化，未设置时由平台决定")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "4096", min = 1d)
    private Integer maxTokens;

    /**
     * 限制「模型回答 + 思维链」的总长度，用于让模型按需输出超长内容，取值 [0, 65536]。
     *
     * <p>仅火山方舟提供该字段；DeepSeek 无对应字段。与 {@link #maxTokens} <b>互斥</b>，
     * 同时设置会被平台直接报错拒绝。
     */
    @JsonProperty("max_completion_tokens")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, supported = false)
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, min = 0d, max = 65536d, note = "与 maxTokens 互斥；设置后 maxTokens 的默认值失效")
    private Integer maxCompletionTokens;

    /**
     * 限制思考的工作量，用于在「更深的推理」与「更快的响应」之间取舍。
     *
     * <p>取值与默认值平台不同：DeepSeek 只接受 {@code high}/{@code max}（默认 {@code high}，
     * {@code low}/{@code medium} 会被映射为 {@code high}、{@code xhigh} 映射为 {@code max}）；
     * 火山方舟接受 {@code minimal}/{@code low}/{@code medium}/{@code high}（默认 {@code medium}，
     * {@code minimal} 等同关闭思考）。
     */
    @JsonProperty("reasoning_effort")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, defaultValue = "high", allowed = {"high", "max"}, note = "low/medium 按 high 处理，xhigh 按 max 处理")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "medium", allowed = {"minimal", "low", "medium", "high"}, note = "minimal 等同关闭思考")
    private String reasoningEffort;

    /**
     * 如值为正，根据新 token 在文本中的出现频率对其进行惩罚，从而降低模型逐字重复的可能性。
     * 取值范围：[-2.0, 2.0]，火山方舟默认 0。
     *
     * <p>DeepSeek 已废弃该参数：传入不会报错，但<b>不会生效</b>，因此对 DeepSeek 直接丢弃。
     */
    @JsonProperty("frequency_penalty")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, supported = false, note = "已废弃，传入不会生效")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "0", min = -2d, max = 2d)
    private Double frequencyPenalty;

    /**
     * 如果值为正，会根据新 token 到目前为止是否出现在文本中对其进行惩罚，从而增加模型谈论新主题的可能性。
     * 取值范围：[-2.0, 2.0]，火山方舟默认 0。
     *
     * <p>DeepSeek 已废弃该参数：传入不会报错，但<b>不会生效</b>，因此对 DeepSeek 直接丢弃。
     */
    @JsonProperty("presence_penalty")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, supported = false, note = "已废弃，传入不会生效")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "0", min = -2d, max = 2d)
    private Double presencePenalty;

    /**
     * 指定模型必须输出的格式：取值 {@code text}（默认）或 {@code json_object}。
     *
     * <p>两家均支持 {@code json_object}；火山方舟另有 {@code json_schema}（结构化输出，beta，
     * 仅部分模型）。使用 JSON 模式时必须同时通过系统或用户消息指示模型生成 JSON，
     * 否则模型可能持续输出空白字符直到触达 token 上限；若 {@code finish_reason="length"}，
     * 内容可能被截断。
     *
     * <p>线格式随能力不同，因此取值集合不通用：对话侧是<b>对象</b>
     * {@code "response_format":{"type":"text"}}（用 {@code name = "response_format.type"} 声明嵌套），
     * 生图侧是<b>字符串</b> {@code "response_format":"url"}。
     */
    @JsonProperty("response_format")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, name = "response_format.type", defaultValue = "text", allowed = {"text", "json_object"})
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, name = "response_format.type", defaultValue = "text", allowed = {"text", "json_object", "json_schema"})
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, defaultValue = "url", allowed = {"url", "b64_json"}, note = "图片返回形式；url 链接 24 小时内有效，需自行转存；生成的图片固定为 jpeg 格式")
    private String responseFormat;

    /**
     * 停止序列，模型遇到其中任一字符串即停止生成（停止串本身不输出）。
     */
    @JsonProperty("stop")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, maxCount = 16)
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, maxCount = 4, note = "深度思考模型不支持该字段")
    private List<String> stops;

    /**
     * 控制模型是否调用工具：{@code none}（不调用）、{@code auto}（由模型判断）、
     * {@code required}（必须调用），或指定具体工具的对象形式
     * {@code {"type":"function","function":{"name":"..."}}}。
     *
     * <p>默认值取决于是否携带 tools：未传 tools 时默认 {@code none}，传了 tools 时默认 {@code auto}
     * （DeepSeek 明确如此；火山方舟对话接口的文档只列出 {@code tools}，「工具选择模式 none/auto/required」
     * 见其 Responses API 文档，Chat 侧取值需按模型能力文档确认后再下发）。
     */
    @JsonProperty("tool_choice")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, allowed = {"none", "auto", "required"}, note = "未传 tools 时默认 none；传了 tools 时默认 auto；指定具体工具需下发对象形式")
    private String toolChoice;

    /**
     * 是否允许模型一次返回多个待调用工具。
     *
     * <p>火山方舟默认 true；DeepSeek 文档未列出该字段，为避免下发未知参数，对 DeepSeek 丢弃。
     */
    @JsonProperty("parallel_tool_calls")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, supported = false, note = "DeepSeek 文档未列出该字段")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "true")
    private Boolean parallelToolCalls;

    /**
     * 是否返回输出 token 的对数概率，默认 false。
     *
     * <p>两家都不在思考模式下支持该字段：DeepSeek 思考模式下发会<b>报错</b>，
     * 火山方舟深度思考模型不支持该字段。
     */
    @JsonProperty("logprobs")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, defaultValue = "false", note = "思考模式下不支持且会报错")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "false", note = "深度思考模型不支持该字段")
    private Boolean logprobs;

    /**
     * 每个输出 token 位置上返回多少个最可能 token 及其对数概率，取值 [0, 20]，默认 0。
     *
     * <p>仅当 {@link #logprobs} 为 true 时可设置；思考模式下与 {@code logprobs} 同样不被支持。
     */
    @JsonProperty("top_logprobs")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, min = 0d, max = 20d, note = "仅当 logprobs 为 true 时可设置")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "0", min = 0d, max = 20d, note = "深度思考模型不支持；仅当 logprobs 为 true 时可设置")
    private Integer topLogprobs;

    /**
     * 服务等级：{@code auto}（优先使用 TPM 保障包额度）或 {@code default}（维持默认等级），默认 auto。
     *
     * <p>仅火山方舟提供该字段；DeepSeek 无对应字段。
     */
    @JsonProperty("service_tier")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, supported = false)
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "auto", allowed = {"auto", "default"})
    private String serviceTier;

    /**
     * 终端用户标识，用于内容安全审核、KVCache 与调度隔离。
     *
     * <p>取值只允许 {@code [a-zA-Z0-9-_]}，长度不超过 512；不得包含用户隐私信息。
     * DeepSeek 提供该字段；方舟对话接口未提供，故对火山方舟丢弃。
     */
    @JsonProperty("user_id")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, note = "取值 [a-zA-Z0-9-_]，长度不超过 512")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, supported = false, note = "方舟对话接口未提供该字段")
    private String userId;

    /**
     * 是否以流式（SSE）方式返回本次请求的结果。
     *
     * <p>对话场景两家默认均为 {@code false}；置 {@code true} 时若要拿到 token 用量，还需
     * {@link #includeUsage} 一并下发（见该字段说明）。
     * 图片生成场景仅 seedream-4.5 / 4.0 支持流式，按 SSE 逐张返回生成结果。
     */
    @JsonProperty("stream")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, defaultValue = "false")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, defaultValue = "false")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, defaultValue = "false", note = "仅 4.5/4.0 支持流式生图，按 SSE 逐张返回")
    private Boolean stream;

    /**
     * 流式响应是否带上 token 用量：线格式为 {@code "stream_options":{"include_usage":true}}。
     *
     * <p>为什么需要它：OpenAI 兼容家族（DeepSeek、火山方舟等）的流式响应<b>默认不回传 usage</b>，
     * 必须显式开启，否则整条流结束后拿不到输入 / 输出 token 数——非流式没有这个限制。
     *
     * <p>为什么标 {@code streamingOnly}：{@code stream_options} 只在 {@code stream = true} 时合法，
     * 非流式下发会被平台直接拒绝，所以该字段仅在流式请求中出现。
     *
     * <p>默认按开启处理（字段为空即下发 true）；确实不需要统计时显式置为 {@code false}。
     */
    @JsonProperty("stream_options.include_usage")
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, streamingOnly = true, defaultValue = "true", note = "不开启则流式响应不带 usage")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, streamingOnly = true, defaultValue = "true", note = "不开启则流式响应不带 usage")
    private Boolean includeUsage;

    /**
     * 模型可能会调用的 tool 的列表。
     *
     * <p>文本对话侧：目前仅支持 function 作为工具，用本参数提供以 JSON 作为输入参数的 function 列表，
     * tool 名称必须唯一（DeepSeek 最多 128 个）。
     *
     * <p>图片生成侧：该字段用于开启<b>联网搜索</b>，线格式为 {@code "tools":[{"type":"web_search"}]}
     * ——与对话侧的 function 工具是同一字段名、不同工具类型；开启后由模型按提示词自主决定是否搜索互联网内容
     * （如商品、天气），会带来一定时延，实际搜索次数见响应 {@code usage.tool_usage.web_search}。
     * 仅 Seedream 5.0 lite 支持联网搜索（5.0 pro / 4.5 / 4.0 不支持）。
     */
    @ProviderAdapter(type = ModelProviderEnum.DEEPSEEK, capability = CapabilityKindEnum.CHAT, maxCount = 128)
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.CHAT, note = "方舟对话接口的 function 工具；能否下发 tool_choice 以模型能力文档为准")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, note = "图片生成侧仅支持联网搜索工具 {\"type\":\"web_search\"}，且仅 Seedream 5.0 lite 支持")
    private List<ToolContext> tools;
}
