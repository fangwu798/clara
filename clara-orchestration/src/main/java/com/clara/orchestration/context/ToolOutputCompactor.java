package com.clara.orchestration.context;

import com.clara.core.message.IContent;
import com.clara.enums.RoleEnum;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 历史工具结果压缩器：把「当前提问之前」的历史工具结果裁到给定长度，控制请求体膨胀。
 *
 * <p>为什么需要：一次工具调用的输出往往很大（目录树、检索命中、文件内容），且会长期留在会话历史里随每轮
 * 请求重发，是请求体膨胀的主要来源。会话历史默认不设条数/大小上限时，单轮请求体会被历史工具结果淹没
 * （实测单次请求可达数十万字符）。
 *
 * <p>压缩口径（对齐「压缩工具上下文、不压缩用户提问」）：
 * <ul>
 *   <li>只压缩 {@link RoleEnum#TOOL}（工具结果）消息，且只压缩最后一条用户消息<b>之前</b>的历史部分；</li>
 *   <li>本轮（最后一条用户消息之后）的工具结果原样保留——它正是模型当前推理要用的，压缩会导致答错；</li>
 *   <li>用户提问、助手回复、系统指令与工具调用声明一律不改写；</li>
 *   <li>超长工具结果保留首尾、中间以省略标记替代，长度不超上限的保持原对象不动。</li>
 * </ul>
 *
 * <p>线程安全：无状态，全部为静态纯函数，可并发调用；输入列表与元素不被修改。
 */
public final class ToolOutputCompactor {

    /**
     * 省略标记模板，占位符为被省略的字符数。
     */
    private static final String OMISSION_MARKER =
            "%n…（历史工具结果过长已压缩，省略 %d 字）…%n";

    private ToolOutputCompactor() {
    }

    /**
     * 压缩历史工具结果。
     *
     * @param messages    窗口内消息，可为 null
     * @param maxChars    单条工具结果最大保留字符数；{@code <= 0} 表示不压缩
     * @return 压缩后的消息列表，非 null；无需压缩时返回原列表
     */
    public static List<IContent> compact(List<IContent> messages, int maxChars) {
        if (messages == null || messages.isEmpty() || maxChars <= 0) {
            return messages == null ? Collections.<IContent>emptyList() : messages;
        }
        int boundary = lastUserIndex(messages);
        if (boundary <= 0) {
            return messages;
        }
        List<IContent> result = new ArrayList<>(messages.size());
        boolean changed = false;
        for (int index = 0; index < messages.size(); index++) {
            IContent message = messages.get(index);
            IContent compressed = index < boundary ? compactToolResult(message, maxChars) : message;
            changed = changed || compressed != message;
            result.add(compressed);
        }
        return changed ? result : messages;
    }

    /**
     * 定位最后一条用户消息的下标；其之前为历史，其后为本轮。
     *
     * @param messages 消息列表，非 null
     * @return 最后一条用户消息的下标；不存在时返回 -1
     */
    private static int lastUserIndex(List<IContent> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            IContent message = messages.get(index);
            if (message != null && message.getRoleType() == RoleEnum.USER
                    && message instanceof UserContent) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 压缩单条工具结果：非工具结果或未超限时返回原对象。
     *
     * @param message  待处理消息，可为 null
     * @param maxChars 最大保留字符数，正数
     * @return 压缩后的工具结果；无需压缩时返回原对象
     */
    private static IContent compactToolResult(IContent message, int maxChars) {
        if (!(message instanceof ToolContent) || message.getRoleType() != RoleEnum.TOOL) {
            return message;
        }
        String content = ((ToolContent) message).getContent();
        if (content == null || content.length() <= maxChars) {
            return message;
        }
        int head = maxChars / 2;
        int tail = maxChars - head;
        String omitted = String.format(OMISSION_MARKER, content.length() - maxChars);
        String compressed = content.substring(0, head) + omitted
                + content.substring(content.length() - tail);
        return new ToolContent(((ToolContent) message).getToolCallId(), compressed);
    }
}
