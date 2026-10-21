package com.clara.adapter.console;

import com.clara.core.message.IContent;
import com.clara.enums.RoleEnum;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.TokenUsage;
import com.clara.usage.TokenCountFormatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 聊天记录渲染：从持久化消息中筛出「用户 / 助手」对话，压缩空白并按行输出，供启动回顾与
 * {@code /history} 指令共用。
 *
 * <p>为什么只保留用户与助手：工具调用与工具结果是编排过程的中间产物（内容长、可读性差），
 * 用户关心的「聊天记录」是双方说过的话；把它们排除后，一条记录对应一次真实的交流。
 *
 * <p>为什么每条要截断：历史可能包含整篇文档或多轮长回答，原样打印会淹没控制台；截断只影响展示，
 * 模型上下文仍使用完整历史。
 */
public final class ConversationHistoryFormatter {

    /**
     * 默认单条消息展示的最大字符数。
     */
    public static final int DEFAULT_MAX_CHARS = 300;

    /**
     * 显示的编号与行首缩进。
     */
    private static final String LINE_PREFIX = "  [";

    /**
     * 连续空白折叠为单个空格，避免多行内容破坏「一行一条」的排版。
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ConversationHistoryFormatter() {
    }

    /**
     * 渲染聊天记录。
     *
     * @param messages    全部持久化消息，可为 null
     * @param maxMessages 最多渲染的条数；小于等于 0 表示全部
     * @return 渲染后的行，非 null
     */
    public static List<String> render(List<IContent> messages, int maxMessages) {
        return render(messages, maxMessages, DEFAULT_MAX_CHARS);
    }

    /**
     * 渲染聊天记录。
     *
     * @param messages    全部持久化消息，可为 null
     * @param maxMessages 最多渲染的条数；小于等于 0 表示全部
     * @param maxChars    单条消息最大字符数；小于等于 0 时取默认值
     * @return 渲染后的行，非 null
     */
    public static List<String> render(List<IContent> messages, int maxMessages, int maxChars) {
        List<IContent> chat = chatMessages(messages);
        if (chat.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
        int from = maxMessages <= 0 ? 0 : Math.max(0, chat.size() - maxMessages);
        List<String> lines = new ArrayList<>(chat.size() - from);
        for (int index = from; index < chat.size(); index++) {
            IContent message = chat.get(index);
            String text = truncate(collapse(textOf(message)), limit);
            if (text.isEmpty()) {
                continue;
            }
            lines.add(LINE_PREFIX + (index + 1) + "] " + labelOf(message.getRoleType()) + "：" + text
                    + usageSuffix(message));
        }
        return lines;
    }

    /**
     * 提取历史用户输入（按时间顺序），供上 / 下方向键召回。
     *
     * @param messages 全部持久化消息，可为 null
     * @return 非空白的用户输入，非 null
     */
    public static List<String> userInputs(List<IContent> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> inputs = new ArrayList<>();
        for (IContent message : messages) {
            if (message == null || message.getRoleType() != RoleEnum.USER) {
                continue;
            }
            String text = textOf(message).trim();
            if (!text.isEmpty()) {
                inputs.add(text);
            }
        }
        return inputs;
    }

    /**
     * 统计聊天记录条数（仅用户与助手）。
     *
     * @param messages 全部持久化消息，可为 null
     * @return 条数
     */
    public static int count(List<IContent> messages) {
        return chatMessages(messages).size();
    }

    /**
     * 过滤出用户与助手消息。
     */
    private static List<IContent> chatMessages(List<IContent> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        List<IContent> chat = new ArrayList<>();
        for (IContent message : messages) {
            if (message == null) {
                continue;
            }
            RoleEnum role = message.getRoleType();
            if ((role == RoleEnum.USER || role == RoleEnum.ASSISTANT) && !textOf(message).trim().isEmpty()) {
                chat.add(message);
            }
        }
        return chat;
    }

    /**
     * 读取消息文本：兼容字符串正文与多段正文（文本 + 图片）。
     */
    private static String textOf(IContent message) {
        Object content = message.toCompletionMessage().get("content");
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof List) {
            StringBuilder text = new StringBuilder();
            for (Object part : (List<?>) content) {
                if (!(part instanceof Map)) {
                    continue;
                }
                Object value = ((Map<?, ?>) part).get("text");
                if (value instanceof String && !((String) value).isEmpty()) {
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(value);
                }
            }
            return text.toString();
        }
        return "";
    }

    /**
     * 折叠连续空白。
     */
    private static String collapse(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    /**
     * 截断超长文本并追加省略号。
     */
    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }

    /**
     * 助手消息的 token 用量后缀；用户消息或旧历史没有用量时返回空串。
     *
     * @param message 历史消息，非 null
     * @return 形如 {@code [tokens 输入=12，输出=34，合计=46]} 的后缀，或空串
     */
    private static String usageSuffix(IContent message) {
        if (!(message instanceof AssistantContent)) {
            return "";
        }
        TokenUsage usage = ((AssistantContent) message).getUsage();
        if (usage == null) {
            return "";
        }
        long input = usage.getInputTokens();
        long output = usage.getOutputTokens();
        long total = usage.getTotalTokens() == null || usage.getTotalTokens() <= 0
                ? input + output : usage.getTotalTokens();
        if (total <= 0L) {
            return "";
        }
        return "  [tokens 输入=" + TokenCountFormatter.format(input)
                + "，输出=" + TokenCountFormatter.format(output)
                + "，合计=" + TokenCountFormatter.format(total) + "]";
    }

    /**
     * 角色显示名。
     */
    private static String labelOf(RoleEnum role) {
        return role == RoleEnum.USER ? "用户" : "助手";
    }
}
