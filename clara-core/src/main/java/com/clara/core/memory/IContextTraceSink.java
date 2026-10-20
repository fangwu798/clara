package com.clara.core.memory;

import java.util.List;

/**
 * 上下文来源观测口：把「本轮从外部知识源取到了什么」报给宿主（控制台 / 详细日志）。
 *
 * <p>是什么、在哪一层：core 定义的契约接口（包 {@code com.clara.core.memory}）——生产侧调用
 * （代码知识图谱补充、语义记忆召回），宿主侧实现（控制台打印）。
 *
 * <p>为什么需要它：图谱补充与向量召回都是「命中才注入、未命中静默」的增强能力——
 * 从对话结果完全看不出它们有没有参与、命中几条、耗时多少、阈值卡在哪儿。排查「为什么模型没用图谱」
 * 这类问题时，只能靠这一层观测。
 *
 * <p>失败语义：上报是旁路，实现方不应抛异常；生产侧也只做尽力上报，上报本身失败不改变
 * 本轮对话行为。
 */
public interface IContextTraceSink {

    /**
     * 上报一次知识源检索结果。
     *
     * @param source        知识源标识（稳定英文标识，如 {@code code_graph}、{@code semantic_recall}），
     *                      展示成什么名字由宿主决定
     * @param hits          可注入的条目数；0 表示这次没有命中
     * @param elapsedMillis 本次检索耗时（毫秒）
     * @param details       命中明细，一行一条（如阈值 / 相似度 / 片段）；无明细时为空列表，非 null
     */
    void onTrace(String source, int hits, long elapsedMillis, List<String> details);

    /**
     * 上报一次阶段进展：用于意图判断、计划生成与计划校验这类「有明确阶段、但不是检索命中」的过程。
     *
     * <p>与 {@link #onTrace(String, int, long, List)} 的区别：命中接口的状态由命中条数推导，
     * 本方法直接给出状态文本（如「判断中」「复杂任务」「校验通过」），宿主可原样展示。
     * 默认实现退化为命中接口（成功记为 1 条、失败记为 0 条），未适配阶段展示的宿主
     * 仍能收到事件，不会丢失观测。
     *
     * @param source        观测源标识（稳定英文标识，如 {@code intent}、{@code validation}），
     *                      展示成什么名字由宿主决定
     * @param status        阶段状态文本，非空
     * @param success       阶段是否成功；宿主可据此选择颜色
     * @param elapsedMillis 阶段耗时（毫秒）
     * @param details       阶段明细，一行一条；无明细时为空列表，非 null
     */
    default void onTrace(String source, String status, boolean success, long elapsedMillis, List<String> details) {
        onTrace(source, success ? 1 : 0, elapsedMillis, details);
    }
}
