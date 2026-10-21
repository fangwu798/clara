package com.clara.orchestration.context;

import com.clara.core.message.IContent;
import com.clara.enums.RoleEnum;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具结果压缩器：按「总量预算 + 最近优先」把单次请求里的工具输出压到可控范围。
 *
 * <p>为什么需要：一次工具调用的输出往往很大（目录树、检索命中、文件内容），且会随每一轮模型调用
 * 重新发送；同一个提问里连续调用几十次工具时，这些输出会全部堆在请求中，把上下文撑爆。只按「历史 /
 * 本轮」划线并不够——本轮的工具结果同样需要预算约束。
 *
 * <p>压缩口径：
 * <ul>
 *   <li>只处理 {@link RoleEnum#TOOL}（工具结果）消息；用户提问、助手回复、系统指令一律不改写；</li>
 *   <li>从最新一条工具结果向前，在「最近保留预算」（总预算的 50%）内原样保留，让模型当前推理
 *       依赖的上下文尽量完整；</li>
 *   <li>其余工具结果按剩余预算分摊单条额度（不超过 {@code maxChars}），保留首尾、中间以省略标记替代；</li>
 *   <li>预算用尽时单条仍保留 {@value #MIN_COMPACT_CHARS} 字符，保证工具名、参数与结论可见；</li>
 *   <li>预算为 -1 时退化为「只压缩当前提问之前的历史结果」，与旧行为一致。</li>
 * </ul>
 *
 * <p>线程安全：无状态，全部为静态纯函数，可并发调用；输入列表与元素不被修改。
 */
public final class ToolOutputCompactor {

    /**
     * 省略标记模板，占位符为被省略的字符数。
     */
    private static final String OMISSION_MARKER =
            "%n…（工具结果过长已压缩，省略 %d 字）…%n";

    /**
     * 最近结果可动用的预算占比：一半预算用于原样保留最新的连续若干条工具结果。
     */
    private static final int RECENT_BUDGET_PERCENT = 50;

    /**
     * 单条压缩后的最小保留字符数：预算再紧也保留首尾，避免只剩省略标记。
     */
    private static final int MIN_COMPACT_CHARS = 400;

    private ToolOutputCompactor() {
    }

    /**
     * 只压缩当前提问之前的历史工具结果（兼容旧行为，不限制本轮与总量）。
     *
     * @param messages 窗口内消息，可为 null
     * @param maxChars 单条工具结果最大保留字符数；{@code <= 0} 表示不压缩
     * @return 压缩后的消息列表，非 null；无需压缩时返回原列表
     */
    public static List<IContent> compact(List<IContent> messages, int maxChars) {
        return compact(messages, maxChars, -1);
    }

    /**
     * 按「总量预算 + 最近优先」压缩工具结果。
     *
     * @param messages    窗口内消息，可为 null
     * @param maxChars    压缩后单条工具结果最大保留字符数；{@code <= 0} 表示不限制单条
     * @param budgetChars 单次请求工具结果总字符预算；{@code <= 0} 表示不限制总量（退化为旧行为）
     * @return 压缩后的消息列表，非 null；无需压缩时返回原列表
     */
    public static List<IContent> compact(List<IContent> messages, int maxChars, int budgetChars) {
        if (messages == null || messages.isEmpty() || (maxChars <= 0 && budgetChars <= 0)) {
            return messages == null ? Collections.emptyList() : messages;
        }
        if (budgetChars <= 0) {
            return compactHistoryOnly(messages, maxChars);
        }

        List<Integer> toolIndexes = toolIndexes(messages);
        if (toolIndexes.isEmpty()) {
            return messages;
        }

        boolean[] keepWhole = keepNewestWithinBudget(messages, toolIndexes, budgetChars);
        boolean[] toCompact = new boolean[messages.size()];
        long keptChars = 0L;
        int compactCount = 0;
        for (Integer index : toolIndexes) {
            if (keepWhole[index]) {
                keptChars += contentLength(messages.get(index));
            } else {
                toCompact[index] = true;
                compactCount++;
            }
        }
        if (compactCount == 0) {
            return messages;
        }

        int newestIndex = toolIndexes.get(toolIndexes.size() - 1);
        boolean newestCompacted = toCompact[newestIndex];
        // 最新一条若放不进「最近保留预算」，仍单独保留总预算的一半：它是本轮推理的直接依据，不能只给单条上限。
        long newestReserve = newestCompacted
                ? Math.min(contentLength(messages.get(newestIndex)), (long) budgetChars / 2L)
                : 0L;
        int otherCount = compactCount - (newestCompacted ? 1 : 0);
        long remaining = Math.max(0L, budgetChars - keptChars - newestReserve);
        int otherLimit = otherCount == 0 ? 0 : perMessageLimit(maxChars, remaining, otherCount);
        int newestLimit = newestCompacted ? (int) Math.max(MIN_COMPACT_CHARS, newestReserve) : 0;
        List<IContent> result = new ArrayList<>(messages.size());
        boolean changed = false;
        for (int index = 0; index < messages.size(); index++) {
            IContent message = messages.get(index);
            IContent compressed = message;
            if (toCompact[index]) {
                int limit = index == newestIndex ? newestLimit : otherLimit;
                compressed = compactToolResult(message, limit);
            }
            changed = changed || compressed != message;
            result.add(compressed);
        }
        return changed ? result : messages;
    }

    /**
     * 旧口径：只压缩最后一条用户消息之前的工具结果。
     */
    private static List<IContent> compactHistoryOnly(List<IContent> messages, int maxChars) {
        if (maxChars <= 0) {
            return messages;
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
     * 收集工具结果消息的下标。
     */
    private static List<Integer> toolIndexes(List<IContent> messages) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            IContent message = messages.get(index);
            if (message instanceof ToolContent && message.getRoleType() == RoleEnum.TOOL) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    /**
     * 从最新一条向前，在「总预算的一半」内把连续的工具结果标记为原样保留。
     *
     * <p>一旦某条放不进预算就停止，保证保留下来的永远是「最新的一段」，而不是零散分布的结果。
     */
    private static boolean[] keepNewestWithinBudget(List<IContent> messages, List<Integer> toolIndexes,
            int budgetChars) {
        boolean[] keepWhole = new boolean[messages.size()];
        long recentBudget = (long) budgetChars * RECENT_BUDGET_PERCENT / 100L;
        long used = 0L;
        for (int position = toolIndexes.size() - 1; position >= 0; position--) {
            int index = toolIndexes.get(position);
            int length = contentLength(messages.get(index));
            if (used + length > recentBudget) {
                break;
            }
            keepWhole[index] = true;
            used += length;
        }
        return keepWhole;
    }

    /**
     * 计算压缩后单条额度：单条上限与「剩余预算 / 待压缩条数」取较小值，且不低于最小保留字符数。
     */
    private static int perMessageLimit(int maxChars, long remaining, int compactCount) {
        long share = remaining / compactCount;
        long limit = maxChars <= 0 ? share : Math.min(maxChars, share);
        limit = Math.max(MIN_COMPACT_CHARS, limit);
        return limit > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) limit;
    }

    /**
     * 读取工具结果内容长度；非工具结果或内容为 null 时按 0 处理。
     */
    private static int contentLength(IContent message) {
        if (!(message instanceof ToolContent)) {
            return 0;
        }
        String content = ((ToolContent) message).getContent();
        return content == null ? 0 : content.length();
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
            if (message != null && message.getRoleType() == RoleEnum.USER && message instanceof UserContent) {
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
        String compressed = content.substring(0, head) + omitted + content.substring(content.length() - tail);
        return new ToolContent(((ToolContent) message).getToolCallId(), compressed);
    }
}
