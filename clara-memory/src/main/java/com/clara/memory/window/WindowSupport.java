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
     * 按窗口策略裁剪历史：system 指令永居首位、不被淘汰；其余消息按「对话轮次」从最旧开始整组淘汰，
     * 并至少保留最近 {@link WindowPolicy#getRecentTurnsKept()} 轮。
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
        List<IContent> system = new ArrayList<>();
        List<IContent> body = new ArrayList<>();
        for (IContent message : messages) {
            if (isSystemInstruction(message)) {
                system.add(message);
            } else {
                body.add(message);
            }
        }
        List<List<IContent>> remaining = new ArrayList<>(groupConversationTurns(body));
        int userTurns = 0;
        for (List<IContent> group : remaining) {
            if (containsUser(group)) {
                userTurns++;
            }
        }
        int minGroups = Math.min(Math.max(policy.getRecentTurnsKept(), 0), userTurns);
        while (remaining.size() > minGroups && !within(system, remaining, policy, estimator)) {
            remaining.remove(0);
        }
        List<IContent> result = new ArrayList<>(system.size());
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
        List<List<IContent>> groups = new ArrayList<>();
        if (body == null) {
            return groups;
        }
        int index = 0;
        while (index < body.size()) {
            IContent message = body.get(index);
            if (startsToolRoundTrip(message)) {
                List<IContent> group = new ArrayList<>();
                group.add(message);
                index++;
                while (index < body.size() && isToolResult(body.get(index))) {
                    group.add(body.get(index));
                    index++;
                }
                groups.add(group);
            } else {
                List<IContent> single = new ArrayList<>();
                single.add(message);
                groups.add(single);
                index++;
            }
        }
        return groups;
    }

    /**
     * 把正文消息按「对话轮次」分组：每条用户消息开启一轮，随后的助手消息、工具调用与工具结果都归入该轮。
     *
     * <p>与 {@link #groupRoundTrips(List)} 的区别：工具往返只是轮次内部的一部分，不能把一轮拆成多组，
     * 否则「最近 N 轮必留」会退化成「最近 N 组必留」。
     *
     * @param body 非 system 消息，不可为 null
     * @return 有序轮次列表；首条用户消息之前的孤立消息单独成组，非 null
     */
    public static List<List<IContent>> groupConversationTurns(List<IContent> body) {
        List<List<IContent>> groups = new ArrayList<>();
        if (body == null) {
            return groups;
        }
        List<IContent> leading = new ArrayList<>();
        List<IContent> current = null;
        for (IContent message : body) {
            if (message != null && message.getRoleType() == RoleEnum.USER) {
                if (current != null) {
                    groups.add(current);
                }
                current = new ArrayList<>();
            } else if (current == null) {
                leading.add(message);
                continue;
            }
            current.add(message);
        }
        if (current != null) {
            groups.add(current);
        }
        if (!leading.isEmpty()) {
            groups.add(0, leading);
        }
        return groups;
    }

    /**
     * 判断一组消息里是否含用户消息；不含用户的孤立组不享受「最近 N 轮必留」的保护。
     */
    private static boolean containsUser(List<IContent> group) {
        for (IContent message : group) {
            if (message != null && message.getRoleType() == RoleEnum.USER) {
                return true;
            }
        }
        return false;
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
        List<IContent> all = new ArrayList<>(system.size());
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
