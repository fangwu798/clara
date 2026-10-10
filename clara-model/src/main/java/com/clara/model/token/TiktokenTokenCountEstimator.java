package com.clara.model.token;

import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.message.IContent;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.RoleEnum;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.List;
import java.util.Optional;

/**
 * 基于真实 BPE tokenizer（jtokkit / tiktoken 词表）的 token 估算器，实现 {@link ITokenCountEstimator}。
 *
 * <p>是什么、在哪一层：{@code clara-model} 的 token 估算实现（{@code com.clara.model.token}），作为
 * {@link HeuristicTokenCountEstimator} 的精确替代。core 只定义契约，这里落地按模型 / 平台选择编码
 * （{@code cl100k_base}、{@code o200k_base} 等）的真实分词计数。jtokkit 纯 Java、零依赖、兼容 Java 8，
 * 内置各编码的 BPE 词表资源，无需网络与手写词表。
 *
 * <p>生命周期与线程安全：实例只持有一个只读的 {@link Encoding}，jtokkit 的编码对象线程安全，可跨线程共享。
 *
 * <p>失败语义：构造期编码名不存在时抛 {@link IllegalArgumentException}；{@link #estimate} 入参为 null 时
 * 抛 {@link IllegalArgumentException}。
 */
public final class TiktokenTokenCountEstimator implements ITokenCountEstimator {

    /**
     * 默认编码名（OpenAI GPT-3.5 / GPT-4 家族使用的 {@code cl100k_base}）。
     */
    public static final String DEFAULT_ENCODING = "cl100k_base";

    /**
     * 共享的默认编码注册表；{@code newDefaultEncodingRegistry} 会预加载全部内置编码。
     */
    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();

    /**
     * 实际执行计数的编码器。
     */
    private final Encoding encoding;

    /**
     * 使用指定编码名创建估算器。
     *
     * @param encodingName 编码名（如 {@code cl100k_base}、{@code o200k_base}），不可为空白
     * @throws IllegalArgumentException encodingName 为空白或未注册时
     */
    public TiktokenTokenCountEstimator(String encodingName) {
        if (encodingName == null || encodingName.trim().isEmpty()) {
            throw new IllegalArgumentException("encodingName must not be blank");
        }
        Optional<Encoding> resolved = REGISTRY.getEncoding(encodingName);
        if (!resolved.isPresent()) {
            throw new IllegalArgumentException("unknown encoding name: " + encodingName);
        }
        this.encoding = resolved.get();
    }

    /**
     * 使用内置编码类型创建估算器。
     *
     * @param encodingType 编码类型，不可为 null
     * @throws IllegalArgumentException encodingType 为 null 时
     */
    public TiktokenTokenCountEstimator(EncodingType encodingType) {
        if (encodingType == null) {
            throw new IllegalArgumentException("encodingType must not be null");
        }
        this.encoding = REGISTRY.getEncoding(encodingType);
    }

    /**
     * 按模型平台选择编码创建估算器。OpenAI 兼容家族（OpenAI / 火山方舟 / DeepSeek / 通义 / Kimi）
     * 统一使用 {@code cl100k_base}；其余平台同样回退到 {@code cl100k_base}（jtokkit 不含 Anthropic 词表）。
     *
     * @param provider 模型平台，可为 null（null 时使用默认编码）
     * @return 按平台选好编码的估算器，非 null
     */
    public static TiktokenTokenCountEstimator forProvider(ModelProviderEnum provider) {
        return new TiktokenTokenCountEstimator(DEFAULT_ENCODING);
    }

    /**
     * 估算一批历史消息的 token 数（真实 BPE 分词）。
     *
     * @param messages 历史消息，不可为 null
     * @return 非负 token 数；合计超过 int 上限时截断为 {@link Integer#MAX_VALUE}
     */
    @Override
    public int estimate(List<IContent> messages) {
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        long total = 0L;
        for (IContent message : messages) {
            total += estimateMessage(message);
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * 估算单条消息的 token 数，按角色类型分派取正文。
     *
     * @param message 消息，可为 null
     * @return 该消息的 token 数；null 时返回 0
     */
    private long estimateMessage(IContent message) {
        if (message == null) {
            return 0L;
        }
        long tokens = 0L;
        RoleEnum role = message.getRoleType();
        if (role == RoleEnum.TOOL_CALL) {
            ToolCallContent toolCall = (ToolCallContent) message;
            tokens += count(toolCall.getContent());
            for (ToolCall call : toolCall.getToolCalls()) {
                ToolCallFunction function = call.getFunction();
                if (function != null) {
                    tokens += count(function.getName());
                    tokens += count(function.getArguments());
                }
            }
        } else if (role == RoleEnum.SYSTEM) {
            tokens += count(((SystemContent) message).getContent());
        } else if (role == RoleEnum.USER) {
            tokens += count(((UserContent) message).getContent());
        } else if (role == RoleEnum.ASSISTANT) {
            tokens += count(((AssistantContent) message).getContent());
        } else if (role == RoleEnum.TOOL) {
            tokens += count(((ToolContent) message).getContent());
        }
        return tokens;
    }

    /**
     * 统计一段文本的 token 数；空文本计 0。
     *
     * @param text 正文文本，可为 null
     * @return token 数；null 或空串返回 0
     */
    private int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokensOrdinary(text);
    }
}
