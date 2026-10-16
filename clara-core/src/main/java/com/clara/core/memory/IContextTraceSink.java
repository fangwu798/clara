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
}
