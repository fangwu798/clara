package com.clara.kernel.http;

import com.clara.annoation.ResponseAdapter;
import com.clara.annoation.ProtocolResponse;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.FinishReasonEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.ProtocolEnum;
import com.clara.enums.StreamEventEnum;
import com.clara.kernel.api.IResponse;
import com.clara.kernel.message.delta.ImageItem;
import com.clara.tool.ResponsesToolCall;
import com.clara.tool.ToolCall;
import lombok.Data;

import java.util.List;

/**
 * 统一响应对象：所有平台、所有能力的返回都解到本类，字段与平台的对应关系由 {@link ResponseAdapter} 声明。
 *
 * <p>为什么只有一个响应类：调用方不需要知道自己调的是 DeepSeek 还是方舟——平台与能力取自线程上下文
 * （{@code ConvContext}），字段路径由注解描述，因此「同一个字段在不同平台的不同取值位置」是数据而不是
 * 代码分支。与入参侧的 {@link com.clara.kernel.http.HttpRequest} 对称。
 *
 * <p>使用方法：
 * <ol>
 *   <li>由 {@code ProviderResponseDecoder} 解码后返回，调用方不自行构造；</li>
 *   <li>先看 {@link #getRaw()} 判状态码与错误体，再读业务字段；</li>
 *   <li>流式调用时，增量字段（{@code content} / {@code reasoning}）会被累加，流结束后读同一对象的最终值。</li>
 * </ol>
 *
 * <p>字段口径：
 * <ul>
 *   <li>{@code content} / {@code reasoning}：正文与思维链，OpenAI 兼容家族在 {@code choices[0]} 下，
 *       流式对应 {@code delta} 子路径；</li>
 *   <li>{@code toolCalls}：工具调用数组，元素结构见 {@link ToolCall}；</li>
 *   <li>{@code finishReason}：结束原因；流式下它只是结束状态字段，<b>不构成终止条件</b>——流的终止以 SSE 的
 *       {@code [DONE]} 为准，在结束原因后平台还会补发只带 {@code usage} 的分片；</li>
 *   <li>{@code inputTokens} / {@code outputTokens} / {@code totalTokens} / {@code cachedTokens}：token 用量，
 *       缓存命中量的平台路径不同，两条规则按平台分别命中；</li>
 *   <li>{@code images} / {@code generatedImages} / {@code webSearchCount}：图片生成结果与用量
 *       （{@code capability = IMAGE}），单张图片结构见 {@link ImageItem}。</li>
 * </ul>
 *
 * <p>线程安全：可变 DTO（解码器逐个字段写入），只在解码线程与调用线程之间传递，不要跨线程共享同一实例。
 */
@Data
public class HttpResponse implements IResponse {

    /**
     * 原始 HTTP 响应，由解码器回填；未解码时为 null。
     */
    private RawHttpResponse raw;

    /**
     * 正文内容：同步取 {@code choices[0].message.content}，流式累加 {@code choices[0].delta.content}。
     */
    @ResponseAdapter(path = "choices[0].message.content", streamPath = "choices[0].delta.content", event = StreamEventEnum.TEXT_DELTA)
    @ProtocolResponse(protocol = ProtocolEnum.RESPONSE, path = "output[type=message].content[0].text", streamPath = "delta", streamEvent = "response.output_text.delta")
    private String content;

    /**
     * 思维链内容：DeepSeek 思考模式返回 {@code reasoning_content}，流式为 {@code delta.reasoning_content}。
     */
    @ResponseAdapter(path = "choices[0].message.reasoning_content", streamPath = "choices[0].delta.reasoning_content", event = StreamEventEnum.REASONING_DELTA)
    @ProtocolResponse(protocol = ProtocolEnum.RESPONSE, path = "output[type=reasoning].content[0].text", streamPath = "delta", streamEvent = "response.reasoning_text.delta")
    private String reasoning;

    /**
     * 工具调用列表；流式下 OpenAI 兼容家族以 {@code delta.tool_calls} 分片下发。
     */
    @ResponseAdapter(path = "choices[0].message.tool_calls", streamPath = "choices[0].delta.tool_calls", event = StreamEventEnum.TOOL_ARGUMENT_DELTA)
    private List<ToolCall> toolCalls;

    /**
     * Responses 协议的工具调用列表；流式下在 {@code response.completed} 事件里从 {@code response.output} 一次性
     * 解析 {@code function_call} 条目，类型为 {@link ResponsesToolCall}（{@code type=function_call}）。
     */
    @ResponseAdapter(event = StreamEventEnum.TOOL_ARGUMENT_DELTA)
    @ProtocolResponse(protocol = ProtocolEnum.RESPONSE, path = "output", streamPath = "response.output", streamEvent = "response.completed")
    private List<ResponsesToolCall> responsesToolCalls;

    /**
     * 结束原因；同步按 {@code choices[0].finish_reason} 取值，流式命中 {@code terminalPath} 时写入。
     *
     * <p>命中只代表平台给出了结束原因，不代表流已结束：流以 SSE 的 {@code [DONE]} 终止，之后还可能有
     * 只带 {@code usage} 的分片。
     */
    @ResponseAdapter(path = "choices[0].finish_reason", terminalPath = "choices[0].finish_reason")
    @ProtocolResponse(protocol = ProtocolEnum.RESPONSE, path = "status", terminalPath = "status")
    private FinishReasonEnum finishReason;

    /**
     * 平台响应 ID。
     */
    @ResponseAdapter(path = "id")
    private String id;

    /**
     * 平台回显的模型 ID。
     */
    @ResponseAdapter(path = "model")
    private String model;

    /**
     * 输入 token 数。
     */
    @ResponseAdapter(path = "usage.prompt_tokens")
    @ProtocolResponse(protocol = ProtocolEnum.RESPONSE, path = "usage.input_tokens")
    private Integer inputTokens;

    /**
     * 输出 token 数。
     */
    @ResponseAdapter(path = "usage.completion_tokens")
    @ProtocolResponse(protocol = ProtocolEnum.RESPONSE, path = "usage.output_tokens")
    private Integer outputTokens;

    /**
     * 总 token 数；平台未提供时为空，由调用方按输入加输出推导。
     */
    @ResponseAdapter(path = "usage.total_tokens")
    @ProtocolResponse(protocol = ProtocolEnum.RESPONSE, path = "usage.total_tokens")
    private Integer totalTokens;

    /**
     * 命中上下缓存的输入 token 数；各平台路径不同。
     */
    @ResponseAdapter(type = {ModelProviderEnum.DEEPSEEK}, path = "usage.prompt_cache_hit_tokens", note = "DeepSeek 用 prompt_cache_hit_tokens")
    @ResponseAdapter(type = {ModelProviderEnum.VOLCENGINE, ModelProviderEnum.OPENAI, ModelProviderEnum.ALIBABA}, path = "usage.prompt_tokens_details.cached_tokens", note = "方舟 / OpenAI / 通义用 prompt_tokens_details.cached_tokens")
    @ProtocolResponse(protocol = ProtocolEnum.RESPONSE, path = "usage.input_tokens_details.cached_tokens")
    private Integer cachedTokens;

    /**
     * 生成的图片列表（{@code capability = IMAGE}，方舟 {@code data[]}）。
     */
    @ResponseAdapter(capability = CapabilityKindEnum.IMAGE, path = "data")
    private List<ImageItem> images;

    /**
     * 本次生成的图片张数（方舟 {@code usage.generated_images}）。
     */
    @ResponseAdapter(capability = CapabilityKindEnum.IMAGE, path = "usage.generated_images")
    private Integer generatedImages;

    /**
     * 联网搜索实际发生的次数（方舟 {@code usage.tool_usage.web_search}，0 表示未搜索）。
     */
    @ResponseAdapter(capability = CapabilityKindEnum.IMAGE, path = "usage.tool_usage.web_search")
    private Integer webSearchCount;

    @Override
    public <T extends IResponse> Class<T> getCls() {
        return (Class<T>) HttpResponse.class;
    }

    @Override
    public RawHttpResponse toResponse() {
        return raw;
    }
}
