package com.clara.model.provider.deepseek;

import com.clara.model.api.ChatRequest;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.ProviderOptions;
import com.clara.model.api.Role;
import com.clara.model.provider.compatible.OpenAiCompatibleOptions;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 定义 DeepSeek 对话的平台专属请求选项，并负责把这些选项投影到 OpenAI 兼容请求体。
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@code prefixCompletion}：是否开启前缀补全（Beta），即让模型接着最后一条助手消息继续写，对应请求体的
 *       {@code prefix}；为 null 表示不下发该字段；</li>
 *   <li>{@code reasoningContent}：此前已产生的推理片段，在前缀补全时回填给模型，对应请求体的
 *       {@code reasoning_content}。</li>
 * </ul>
 *
 * <p>使用方式：作为 {@link ChatRequest#getProviderOptions()} 传入，且请求最后一条消息必须是
 * {@link Role#ASSISTANT}，编码时由 {@link #applyTo(ChatRequest, List)} 把字段写入该条消息
 * （而不是写入全部消息）。示例：
 * <pre>{@code
 * ChatRequest request = new ChatRequest(
 *         Arrays.asList(message(Role.USER, "question"), message(Role.ASSISTANT, "prefix")),
 *         null, null, new DeepSeekChatOptions(Boolean.TRUE, "prior reasoning"), context);
 *
 * // 编码后的请求体中，最后一条 assistant 消息形如：
 * // { "role": "assistant", "content": "prefix", "prefix": true, "reasoning_content": "prior reasoning" }
 * }</pre>
 *
 * <p>反例：开启前缀补全但请求以用户消息结尾时抛 {@link IllegalArgumentException}；未设置任何选项时
 * {@link #applyTo(ChatRequest, List)} 是零成本空操作。
 */
@Data
public final class DeepSeekChatOptions implements ProviderOptions, OpenAiCompatibleOptions {
    private final Boolean prefixCompletion;
    private final String reasoningContent;

    public DeepSeekChatOptions(Boolean prefixCompletion, String reasoningContent) {
        if (reasoningContent != null && reasoningContent.trim().isEmpty()) {
            throw new IllegalArgumentException("reasoningContent must not be blank");
        }
        if (reasoningContent != null && !Boolean.TRUE.equals(prefixCompletion)) {
            throw new IllegalArgumentException("reasoningContent requires prefix completion");
        }
        this.prefixCompletion = prefixCompletion;
        this.reasoningContent = reasoningContent;
    }

    public ModelProvider getProvider() {
        return ModelProvider.DEEPSEEK;
    }

    /**
     * 把 {@code prefix} 与 {@code reasoning_content} 写入最后一条助手消息。
     *
     * <p>为什么只能落在最后一条助手消息上：{@code prefix} 的语义是“让模型接着这条助手消息继续写”，
     * {@code reasoning_content} 是这条消息此前已产生的推理片段，两者都只能挂在被续写的那条消息上。
     * 请求若不以助手消息结尾，续写目标不存在、语义不成立，因此在本地直接拒绝，而不是把一个必然失败的
     * 请求发给平台。
     */
    @Override
    public void applyTo(ChatRequest request, List<Map<String, Object>> messages) {
        if (prefixCompletion == null && reasoningContent == null) {
            return;
        }
        int last = request.getMessages().size() - 1;
        if (request.getMessages().get(last).getRole() != Role.ASSISTANT) {
            throw new IllegalArgumentException("DeepSeek prefix completion requires final assistant message");
        }
        Map<String, Object> target = messages.get(last);
        if (prefixCompletion != null) {
            target.put("prefix", prefixCompletion);
        }
        if (reasoningContent != null) {
            target.put("reasoning_content", reasoningContent);
        }
    }
}
