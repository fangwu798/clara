package com.clara.memory.window;

import com.clara.core.memory.ITokenCountEstimator;
import com.clara.dto.memory.WindowPolicy;
import com.clara.enums.RoleEnum;
import com.clara.core.message.IContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 窗口裁剪辅助：提供 system 归一化、工具往返识别、淘汰分组与字节数估算等纯函数。
 */
public final class WindowSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WindowSupport() {
    }

    /**
     * 按窗口策略裁剪历史：system 指令永居首位、不被淘汰；其余消息以「完整工具往返」为一组从最旧开始整组淘汰。
     *
     * @param messages  完整历史，不可为 null
     * @param policy    窗口策略，不可为 null
     * @param estimator token 估算器，仅按百分比维度时需要，可为 null
     * @return 裁剪后的窗口内历史，非 null
     */
    public static List<IContent> trim(List<IContent> messages, WindowPolicy policy,
            ITokenCountEstimator estimator) {
        if (messages == null || policy == null) {
            throw new IllegalArgumentException("messages and policy must not be null");
        }
        List<IContent> system = new ArrayList<IContent>();
        List<IContent> body = new ArrayList<IContent>();
        for (IContent message : messages) {
            if (isSystemInstruction(message)) {
                system.add(message);
            } else {
                body.add(message);
            }
        }
        List<List<IContent>> remaining = new ArrayList<List<IContent>>(groupRoundTrips(body));
        while (!remaining.isEmpty() && !within(system, remaining, policy, estimator)) {
            remaining.remove(0);
        }
        List<IContent> result = new ArrayList<IContent>(system.size());
        result.addAll(system);
        for (List<IContent> group : remaining) {
            result.addAll(group);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 判断消息是否为 system 指令。
     */
    public static boolean isSystemInstruction(IContent message) {
        return message != null && message.getRoleType() == RoleEnum.SYSTEM;
    }

    /**
     * 判断消息是否为工具往返的起点（携带 tool_calls 的助手消息）。
     */
    public static boolean startsToolRoundTrip(IContent message) {
        return message != null && message.getRoleType() == RoleEnum.TOOL_CALL;
    }

    /**
     * 判断消息是否为工具回执。
     */
    public static boolean isToolResult(IContent message) {
        return message != null && message.getRoleType() == RoleEnum.TOOL;
    }

    /**
     * 把正文消息按「完整工具往返」分组：起点与其后连续的工具回执归为一组，其余消息单独成组。
     *
     * @param body 非 system 消息，不可为 null
     * @return 有序分组列表，非 null
     */
    public static List<List<IContent>> groupRoundTrips(List<IContent> body) {
        List<List<IContent>> groups = new ArrayList<List<IContent>>();
        if (body == null) {
            return groups;
        }
        int index = 0;
        while (index < body.size()) {
            IContent message = body.get(index);
            if (startsToolRoundTrip(message)) {
                List<IContent> group = new ArrayList<IContent>();
                group.add(message);
                index++;
                while (index < body.size() && isToolResult(body.get(index))) {
                    group.add(body.get(index));
                    index++;
                }
                groups.add(group);
            } else {
                List<IContent> single = new ArrayList<IContent>();
                single.add(message);
                groups.add(single);
                index++;
            }
        }
        return groups;
    }

    /**
     * 估算一批消息渲染后的总字节数。
     */
    public static long sizeBytes(List<IContent> messages) {
        long total = 0L;
        for (IContent message : messages) {
            total += sizeBytes(message);
        }
        return total;
    }

    /**
     * 估算单条消息渲染后的字节数。
     */
    public static long sizeBytes(IContent message) {
        try {
            return MAPPER.writeValueAsBytes(message.toCompletionMessage()).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to measure message size", e);
        }
    }

    private static boolean within(List<IContent> system, List<List<IContent>> remaining,
            WindowPolicy policy, ITokenCountEstimator estimator) {
        List<IContent> all = new ArrayList<IContent>(system.size());
        all.addAll(system);
        for (List<IContent> group : remaining) {
            all.addAll(group);
        }
        int maxMessages = policy.getMaxMessages();
        if (maxMessages > 0 && all.size() > maxMessages) {
            return false;
        }
        long maxSizeBytes = policy.getMaxSizeBytes();
        if (maxSizeBytes > 0 && sizeBytes(all) > maxSizeBytes) {
            return false;
        }
        int maxTokensPercent = policy.getMaxTokensPercent();
        long contextWindowTokens = policy.getContextWindowTokens();
        if (maxTokensPercent > 0 && contextWindowTokens > 0 && estimator != null) {
            long budget = contextWindowTokens * maxTokensPercent / 100L;
            if (estimator.estimate(all) > budget) {
                return false;
            }
        }
        return true;
    }
}
