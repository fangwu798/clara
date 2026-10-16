package com.clara.core.memory;

import com.clara.core.message.IContent;

import java.util.List;
import java.util.Map;

/**
 * 会话消息的入库文本：把一条消息渲染成写进语义记忆的那段文本（{@code role: 正文}）。
 *
 * <p>为什么抽出来共用：写入端（{@code ConversationIndexingWindowMemory}）按这段文本落库，召回端
 * （{@code SemanticRecallCoordinator}）要判断「命中的条目是不是本轮上下文里已经能看到的东西」，就必须用同一套
 * 渲染规则算出同一段文本；两边只要有一处口径漂移（正文取哪儿、截断多少），去重就会失效，所以做成这里的纯函数共用。
 *
 * <p>线程安全：无状态，全部为静态纯函数；入参消息不被修改。
 */
public final class ConversationMemoryText {

    /**
     * 单条记录写入语义记忆的最大字符数：工具输出可能很大，整段入库既浪费额度也会在召回时挤占上下文。
     */
    public static final int MAX_CONTENT_CHARS = 2000;

    private ConversationMemoryText() {
    }

    /**
     * 取一条消息的入库文本：{@code role: 正文}；多模态消息取其文本片段，工具调用消息退回工具调用摘要。
     *
     * @param message 消息，可为 null
     * @return 入库文本；无可用文本（null 消息、空正文）时返回 null
     */
    public static String of(IContent message) {
        if (message == null) {
            return null;
        }
        Map<String, Object> rendered = message.toCompletionMessage();
        String body = body(rendered);
        if (body.isEmpty()) {
            return null;
        }
        Object role = rendered.get("role");
        String text = (role instanceof String ? (String) role : "message") + ": " + body;
        return text.length() <= MAX_CONTENT_CHARS ? text : text.substring(0, MAX_CONTENT_CHARS);
    }

    /**
     * 从线格式消息里取正文：字符串正文直接用；多模态（数组）取其中的文本片段；否则退回工具调用摘要。
     */
    private static String body(Map<String, Object> rendered) {
        Object content = rendered.get("content");
        if (content instanceof String) {
            return ((String) content).trim();
        }
        if (content instanceof List) {
            return textParts((List<?>) content);
        }
        Object toolCalls = rendered.get("tool_calls");
        return toolCalls instanceof List && !((List<?>) toolCalls).isEmpty()
                ? toolCalls.toString().trim() : "";
    }

    /**
     * 取多模态内容里的文本片段（{@code {"type":"text","text":"..."}}）。
     */
    private static String textParts(List<?> parts) {
        StringBuilder text = new StringBuilder();
        for (Object part : parts) {
            if (!(part instanceof Map)) {
                continue;
            }
            Object value = ((Map<?, ?>) part).get("text");
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(((String) value).trim());
            }
        }
        return text.toString();
    }
}
