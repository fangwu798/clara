package com.clara.usage;

import com.clara.core.message.IContent;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.TokenUsage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史记录的 token 用量汇总：只统计消息落盘时记录的真实用量。
 *
 * <p>各平台都会在响应中返回 token 用量，编排层把「一轮所有模型调用」的用量累加后随最终助手消息落盘，
 * 因此历史消耗可以直接累加，不再按字符估算。升级前写入、没有用量的旧消息只计数提示，不参与统计。
 */
public final class HistoryUsageSummary {

    /** 模型标识缺失时的展示名（升级前写入的旧消息没有模型字段）。 */
    private static final String UNKNOWN_MODEL = "未记录模型";

    /** 历史消息总条数。 */
    private final int messageCount;
    /** 真实记录的累计用量。 */
    private final ProviderTokenUsage recorded;
    /** 没有 token 用量的助手消息条数（升级前的旧历史）。 */
    private final int unrecordedMessages;
    /** 「平台/模型」到历史真实用量的映射。 */
    private final Map<String, ProviderTokenUsage> byModel;

    private HistoryUsageSummary(int messageCount, ProviderTokenUsage recorded, int unrecordedMessages,
            Map<String, ProviderTokenUsage> byModel) {
        this.messageCount = messageCount;
        this.recorded = recorded;
        this.unrecordedMessages = unrecordedMessages;
        this.byModel = Collections.unmodifiableMap(new LinkedHashMap<>(byModel));
    }

    /**
     * 汇总历史用量。
     *
     * @param history 历史消息，可为 null
     * @return 汇总结果，非 null
     */
    public static HistoryUsageSummary of(List<IContent> history) {
        List<IContent> messages = history == null ? Collections.<IContent>emptyList() : history;
        long calls = 0L;
        long input = 0L;
        long output = 0L;
        long total = 0L;
        int unrecorded = 0;
        Map<String, ProviderTokenUsage> byModel = new LinkedHashMap<>();
        for (IContent message : messages) {
            if (!(message instanceof AssistantContent)) {
                continue;
            }
            AssistantContent assistant = (AssistantContent) message;
            TokenUsage usage = assistant.getUsage();
            if (usage == null) {
                unrecorded++;
                continue;
            }
            long messageInput = usage.getInputTokens();
            long messageOutput = usage.getOutputTokens();
            long messageTotal = totalOf(usage);
            calls++;
            input += messageInput;
            output += messageOutput;
            total += messageTotal;
            String model = modelOf(assistant);
            byModel.put(model, byModel.getOrDefault(model, ProviderTokenUsage.EMPTY)
                    .plus(new ProviderTokenUsage(1L, messageInput, messageOutput, messageTotal)));
        }
        return new HistoryUsageSummary(messages.size(), new ProviderTokenUsage(calls, input, output, total),
                unrecorded, byModel);
    }

    /**
     * 渲染历史记录摘要行。
     *
     * @return 单行文本，非 null
     */
    public String summaryLine() {
        StringBuilder line = new StringBuilder("历史记录：").append(messageCount).append(" 条");
        if (recorded.getCalls() == 0L) {
            return line.append("，暂无 token 记录").toString();
        }
        line.append("，已记录 ").append(recorded.getCalls()).append(" 条回答用量（输入 ")
                .append(TokenCountFormatter.format(recorded.getInputTokens()))
                .append(" / 输出 ").append(TokenCountFormatter.format(recorded.getOutputTokens()))
                .append(" / 合计 ").append(TokenCountFormatter.format(recorded.getTotalTokens()))
                .append(" tokens）");
        if (unrecordedMessages > 0) {
            line.append("，另有 ").append(unrecordedMessages).append(" 条助手消息无 token 记录");
        }
        return line.toString();
    }

    /**
     * 没有 token 用量的助手消息条数（升级前的旧历史）。
     *
     * @return 条数，非负
     */
    public int getUnrecordedMessages() {
        return unrecordedMessages;
    }

    /**
     * 历史中真实记录的用量（只含带 token 用量的助手消息）。
     *
     * @return 累计用量，非 null
     */
    public ProviderTokenUsage getRecorded() {
        return recorded;
    }

    /**
     * 按「平台/模型」拆分的历史真实用量，保持首次出现顺序。
     *
     * @return 只读映射，非 null
     */
    public Map<String, ProviderTokenUsage> getByModel() {
        return byModel;
    }

    private static String modelOf(AssistantContent message) {
        String model = message.getModel();
        return model == null || model.trim().isEmpty() ? UNKNOWN_MODEL : model;
    }

    /**
     * 取用量的总 token 数：平台显式返回则以其为准，否则按输入加输出推导。
     */
    private static long totalOf(TokenUsage usage) {
        Integer total = usage.getTotalTokens();
        if (total == null || total <= 0) {
            return (long) usage.getInputTokens() + usage.getOutputTokens();
        }
        return total;
    }
}
