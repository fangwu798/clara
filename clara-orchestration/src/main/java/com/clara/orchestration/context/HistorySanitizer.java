package com.clara.orchestration.context;

import com.clara.core.message.IContent;
import com.clara.enums.RoleEnum;
import com.clara.kernel.message.delta.AssistantContent;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话历史净化：剔除非法的助手消息。
 *
 * <p>为什么需要：正文为空且不带工具调用的 assistant 消息会被平台判为非法
 * （{@code Invalid assistant message: content or tool_calls must be set}）。早期版本会把空回复记为
 * 助手消息而写入历史，这类脏数据会随每轮请求重发导致整轮失败，故在装配与压缩前统一过滤。
 *
 * <p>线程安全：无状态，全部为静态纯函数；入参列表与元素不被修改。
 */
public final class HistorySanitizer {

    private HistorySanitizer() {
    }

    /**
     * 剔除「正文为空且不带工具调用」的非法助手消息。
     *
     * @param messages 消息列表，可为 null
     * @return 过滤后的列表，非 null；无需过滤时返回原列表
     */
    public static List<IContent> dropInvalidAssistantMessages(List<IContent> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? java.util.Collections.emptyList() : messages;
        }
        List<IContent> result = new ArrayList<>(messages.size());
        for (IContent message : messages) {
            if (isInvalidAssistant(message)) {
                continue;
            }
            result.add(message);
        }
        return result.size() == messages.size() ? messages : result;
    }

    /**
     * 判断是否为「正文为空且不带工具调用」的非法助手消息。
     *
     * @param message 待判断消息，可为 null
     * @return 非法时返回 true
     */
    public static boolean isInvalidAssistant(IContent message) {
        if (message == null || message.getRoleType() != RoleEnum.ASSISTANT
                || !(message instanceof AssistantContent)) {
            return false;
        }
        String content = ((AssistantContent) message).getContent();
        return content == null || content.trim().isEmpty();
    }
}
