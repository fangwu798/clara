package com.clara.model.token;

import com.clara.core.memory.ITokenCountEstimator;
import com.clara.enums.RoleEnum;
import com.clara.core.message.IContent;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;

import java.util.List;

/**
 * 基于启发式规则的 token 估算器：按消息正文估算 token 数，作为窗口「按百分比」维度的计算依据。
 *
 * <p>是什么、在哪一层：{@code clara-model} 的 token 估算实现（{@code com.clara.model.token}），落地
 * {@link ITokenCountEstimator} 契约。core 只定义契约，估算规则随模型而异，这里提供一套可注入的默认实现。
 *
 * <p>估算口径（启发式，非精确分词）：中文等全角文字按每字 1 token 计；其余字符按每
 * {@code charsPerToken} 个字符折合 1 token（默认 4，接近英文平均每 token 4 字符的经验值）；每条消息额外计
 * 4 token 的消息框架开销；助手工具调用消息把工具名与入参 JSON 一并计入。按平台 / 模型接入真实 tokenizer
 * （如 tiktoken）可替换本实现或调整 {@code charsPerToken}。
 *
 * <p>生命周期与线程安全：实例只持有一个只读的比例常量，线程安全、可跨线程共享。
 */
public final class HeuristicTokenCountEstimator implements ITokenCountEstimator {

    /**
     * 默认每 token 折合的 ASCII 字符数（英文经验值：平均每 token 约 4 字符）。
     */
    private static final double DEFAULT_CHARS_PER_TOKEN = 4.0;

    /**
     * 每条消息的固定框架开销 token 数。
     */
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    /**
     * 非全角字符折合 token 的比例。
     */
    private final double charsPerToken;

    /**
     * 使用默认比例创建估算器。
     */
    public HeuristicTokenCountEstimator() {
        this(DEFAULT_CHARS_PER_TOKEN);
    }

    /**
     * 使用指定比例创建估算器，便于按平台 / 模型微调。
     *
     * @param charsPerToken 非全角字符每 token 的字符数，必须为正
     * @throws IllegalArgumentException charsPerToken 非正时
     */
    public HeuristicTokenCountEstimator(double charsPerToken) {
        if (charsPerToken <= 0) {
            throw new IllegalArgumentException("charsPerToken must be positive");
        }
        this.charsPerToken = charsPerToken;
    }

    /**
     * 估算一批历史消息的 token 数。
     *
     * @param messages 历史消息，不可为 null
     * @return 非负的 token 估算数；合计超过 int 上限时截断为 {@link Integer#MAX_VALUE}
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
     * @return 该消息的 token 估算数；null 时返回 0
     */
    private long estimateMessage(IContent message) {
        if (message == null) {
            return 0L;
        }
        long tokens = MESSAGE_OVERHEAD_TOKENS;
        RoleEnum role = message.getRoleType();
        if (role == RoleEnum.TOOL_CALL) {
            ToolCallContent toolCall = (ToolCallContent) message;
            tokens += estimateText(toolCall.getContent());
            for (ToolCall call : toolCall.getToolCalls()) {
                ToolCallFunction function = call.getFunction();
                if (function != null) {
                    tokens += estimateText(function.getName());
                    tokens += estimateText(function.getArguments());
                }
            }
        } else if (role == RoleEnum.SYSTEM) {
            tokens += estimateText(((SystemContent) message).getContent());
        } else if (role == RoleEnum.USER) {
            tokens += estimateText(((UserContent) message).getContent());
        } else if (role == RoleEnum.ASSISTANT) {
            tokens += estimateText(((AssistantContent) message).getContent());
        } else if (role == RoleEnum.TOOL) {
            tokens += estimateText(((ToolContent) message).getContent());
        }
        return tokens;
    }

    /**
     * 估算一段文本的 token 数：全角文字每字 1 token，其余字符按 {@link #charsPerToken} 折合。
     *
     * @param text 正文文本，可为 null
     * @return 文本 token 估算数；null 或空串返回 0
     */
    private long estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        long cjk = 0L;
        long other = 0L;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (long) Math.ceil(other / charsPerToken);
    }

    /**
     * 判断码点是否属于全角文字（汉 / 假名 / 谚文），这些文字通常按每字 1 token 估算。
     *
     * @param codePoint 码点
     * @return 属于全角文字时返回 true
     */
    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
