package com.clara.dto.memory;

import lombok.Getter;

/**
 * 话题隔离配置：决定历史对话是否按话题切段，以及切换话题时旧话题保留多少内容。
 *
 * <p>是什么、在哪一层：core 的不可变值对象，供编排层装配上下文时使用。它不是模型调用参数，
 * 而是「历史消息进入本轮请求前如何筛选」的策略配置。
 *
 * <p>为什么需要：普通窗口只看「最近多少轮」，用户从话题 A 切到话题 B 时，A 的近期轮次仍会被
 * 无条件带入，模型容易继续按 A 的结论回答 B。话题隔离把历史分成「当前话题段 / 归档话题段」，
 * 只有当前段默认进入请求，旧段按需召回或由用户显式引用。
 *
 * <p>线程安全：字段全部 final，实例可跨线程共享。
 */
@Getter
public final class TopicIsolationConfig {

    /**
     * 默认话题切换阈值：当前输入与最近用户消息的相似度低于该值时，判定为切换话题。
     */
    public static final double DEFAULT_SHIFT_THRESHOLD = 0.40d;

    /**
     * 是否启用话题隔离。
     */
    private final boolean enabled;

    /**
     * 话题切换相似度阈值，取值在 [0, 1] 区间。
     */
    private final double shiftThreshold;

    /**
     * 话题不明确时，额外保留上一话题最近多少轮；0 表示不保留。
     *
     * <p>只在判定为 {@code AMBIGUOUS} 时生效；明确切换话题时不携带旧轮次。
     */
    private final int carryOverPreviousTurns;

    /**
     * 是否把已归档话题的压缩摘要注入本轮 system 指令；默认关闭。
     */
    private final boolean injectArchivedSummary;

    /**
     * 用户显式引用旧话题时，最多向前查找多少个历史话题段。
     */
    private final int explicitReferenceWindow;

    /**
     * 创建话题隔离配置。
     *
     * @param enabled               是否启用话题隔离
     * @param shiftThreshold        话题切换相似度阈值
     * @param carryOverPreviousTurns 话题不明确时保留的上一话题轮数
     * @param injectArchivedSummary 是否注入归档话题摘要
     * @param explicitReferenceWindow 显式引用时向前查找的话题段数
     * @throws IllegalArgumentException 数值超出允许范围时
     */
    public TopicIsolationConfig(boolean enabled, double shiftThreshold, int carryOverPreviousTurns,
            boolean injectArchivedSummary, int explicitReferenceWindow) {
        if (Double.isNaN(shiftThreshold) || shiftThreshold < 0.0d || shiftThreshold > 1.0d) {
            throw new IllegalArgumentException("shiftThreshold must be within [0, 1]");
        }
        if (carryOverPreviousTurns < 0) {
            throw new IllegalArgumentException("carryOverPreviousTurns must not be negative");
        }
        if (explicitReferenceWindow < 0) {
            throw new IllegalArgumentException("explicitReferenceWindow must not be negative");
        }
        this.enabled = enabled;
        this.shiftThreshold = shiftThreshold;
        this.carryOverPreviousTurns = carryOverPreviousTurns;
        this.injectArchivedSummary = injectArchivedSummary;
        this.explicitReferenceWindow = explicitReferenceWindow;
    }

    /**
     * 默认配置：启用话题隔离，明确切换时不携带旧轮次，不注入归档摘要。
     *
     * @return 默认话题隔离配置，非 null
     */
    public static TopicIsolationConfig defaults() {
        return new TopicIsolationConfig(true, DEFAULT_SHIFT_THRESHOLD, 1, false, 5);
    }

    /**
     * 关闭话题隔离：所有历史仍按原窗口策略进入请求。
     *
     * @return 关闭状态的配置，非 null
     */
    public static TopicIsolationConfig disabled() {
        return new TopicIsolationConfig(false, DEFAULT_SHIFT_THRESHOLD, 0, false, 0);
    }
}
