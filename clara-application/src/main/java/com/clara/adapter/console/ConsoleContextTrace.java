package com.clara.adapter.console;

import com.clara.core.memory.IContextTraceSink;
import com.clara.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 控制台上下文来源观测：把「图谱补充 / 向量召回 / 提示词匹配」的命中结果，以及
 * 「意图判断 / 计划生成 / 计划校验」的阶段进展打成一行摘要 + 明细。
 *
 * <p>是什么、在哪一层：application 的控制台适配器（包 {@code com.clara.adapter.console}），
 * 实现 core 的 {@link IContextTraceSink}；由 {@code ClaraApplication} 装配进 {@code ApplicationBootstrap}。
 *
 * <p>为什么要打这几行：图谱与向量都是「命中才注入、未命中静默」，从对话结果看不出它们
 * 有没有参与、命中几条、耗时多久、阈值卡在哪儿；打印后可直接观察执行情况。
 * 同一份文本由 {@link ConsoleView#emit(String, String)} 同时落详细日志，事后也可回查。
 *
 * <p>排版：标签列按 {@link StringUtils#padRight(String, int)} 的显示宽度（中文按两列算），
 * 所以「[图谱]」「[向量]」「[提示词]」「[意图]」「[计划]」「[校验]」后的状态列在终端里是对齐的。
 *
 * <p>线程安全：只持有只读的视图引用，可跨线程调用。
 */
public final class ConsoleContextTrace implements IContextTraceSink {

    /**
     * 知识源标识：代码知识图谱补充信息（与 {@code CodeGraphSupplement} 一致）。
     */
    public static final String SOURCE_CODE_GRAPH = "code_graph";

    /**
     * 知识源标识：语义记忆（向量召回），与 {@code SemanticRecallCoordinator} 一致。
     */
    public static final String SOURCE_SEMANTIC_RECALL = "semantic_recall";

    /**
     * 知识源标识：提示词向量匹配（{@code SkillMatchCoordinator} 的 PROMPT 检索），与该类中的标识一致。
     */
    public static final String SOURCE_PROMPT_MATCH = "prompt_match";

    /**
     * 观测源标识：意图判断（复杂任务 / 简单任务）。
     */
    public static final String SOURCE_INTENT = "intent";

    /**
     * 观测源标识：动态执行计划（通过校验的步骤清单）。
     */
    public static final String SOURCE_PLAN = "plan";

    /**
     * 观测源标识：动态执行计划的格式校验（工具校验 / 本地兜底校验）。
     */
    public static final String SOURCE_VALIDATION = "validation";

    /**
     * 标签列宽（按显示宽度，中文按两列算）：中文标签下状态列也能对齐。
     */
    private static final int LABEL_WIDTH = 10;

    /**
     * 行首缩进：与启动横幅的其他行保持一致。
     */
    private static final String LINE_INDENT = "  ";

    /**
     * 明细行缩进：LINE_INDENT + LABEL_WIDTH，与状态列起点对齐。
     */
    private static final String DETAIL_INDENT = LINE_INDENT + "          ";

    private final ConsoleView view;

    /**
     * 创建控制台观测口。
     *
     * @param view 控制台视图，不可为 null
     * @throws IllegalArgumentException view 为 null 时
     */
    public ConsoleContextTrace(ConsoleView view) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        this.view = view;
    }

    @Override
    public void onTrace(String source, int hits, long elapsedMillis, List<String> details) {
        emit(lines(source, hits, elapsedMillis, details),
                hits > 0 ? ConsoleView.ANSI_GREEN : ConsoleView.ANSI_GRAY);
    }

    /**
     * 输出阶段进展：状态文本原样展示，成功绿色、失败红色。
     *
     * @param source        观测源标识，可为 null（按未知源展示）
     * @param status        阶段状态文本，为空时按「进行中」展示
     * @param success       阶段是否成功
     * @param elapsedMillis 阶段耗时（毫秒）
     * @param details       阶段明细，可为 null（视为无）
     */
    @Override
    public void onTrace(String source, String status, boolean success, long elapsedMillis, List<String> details) {
        emit(phaseLines(source, status, elapsedMillis, details),
                success ? ConsoleView.ANSI_GREEN : ConsoleView.ANSI_RED);
    }

    /**
     * 首行按来源结果着色，明细行统一灰色。
     *
     * @param rendered 已渲染的行，不可为 null 或空
     * @param colorCode 首行颜色码，非 null
     */
    private void emit(List<String> rendered, String colorCode) {
        view.emit(rendered.get(0), colorCode);
        for (int index = 1; index < rendered.size(); index++) {
            view.emit(rendered.get(index), ConsoleView.ANSI_GRAY);
        }
    }

    /**
     * 渲染观测行：首行是「[标签] 命中 N 条（耗时）」或「[标签] 未命中（耗时）」，其后每个明细一行。
     *
     * <p>抽成静态方法是为了单测：排版规则（标签对照、列宽、明细缩进）不需要拉上整个控制台去验证。
     *
     * @param source        知识源标识，可为 null（按未知源展示）
     * @param hits          命中条数；0 表示未命中
     * @param elapsedMillis 耗时（毫秒）
     * @param details       明细，可为 null（视为无）
     * @return 渲染后的行列表，至少一行，非 null
     */
    static List<String> lines(String source, int hits, long elapsedMillis, List<String> details) {
        String status = hits > 0 ? "命中 " + hits + " 条" : "未命中";
        return render(source, status, elapsedMillis, details);
    }

    /**
     * 渲染阶段行：首行是「[标签] 状态（耗时）」，其后每个明细一行。
     *
     * @param source        观测源标识，可为 null（按未知源展示）
     * @param status        阶段状态文本；为空时按「进行中」展示
     * @param elapsedMillis 阶段耗时（毫秒）
     * @param details       阶段明细，可为 null（视为无）
     * @return 渲染后的行列表，至少一行，非 null
     */
    static List<String> phaseLines(String source, String status, long elapsedMillis, List<String> details) {
        String text = status == null || status.trim().isEmpty() ? "进行中" : status.trim();
        return render(source, text, elapsedMillis, details);
    }

    /**
     * 渲染通用观测行：标签列按显示宽度补齐，明细行与状态列起点对齐。
     *
     * @param source        观测源标识，可为 null（按未知源展示）
     * @param status        状态文本，非 null
     * @param elapsedMillis 耗时（毫秒）
     * @param details       明细，可为 null（视为无）
     * @return 渲染后的行列表，至少一行，非 null
     */
    private static List<String> render(String source, String status, long elapsedMillis, List<String> details) {
        List<String> rendered = new ArrayList<>();
        String label = "[" + label(source) + "]";
        rendered.add(LINE_INDENT + StringUtils.padRight(label, LABEL_WIDTH) + status
                + "（" + elapsedMillis + "ms）");
        if (details != null) {
            for (String detail : details) {
                if (detail != null && !detail.trim().isEmpty()) {
                    rendered.add(DETAIL_INDENT + detail.trim());
                }
            }
        }
        return rendered;
    }

    /**
     * 把知识源标识换成人看的标签；未知标识原样展示，方便新增来源时不至于看不到。
     *
     * @param source 知识源标识，可为 null
     * @return 展示标签，非 null
     */
    private static String label(String source) {
        if (SOURCE_CODE_GRAPH.equals(source)) {
            return "图谱";
        }
        if (SOURCE_PROMPT_MATCH.equals(source)) {
            return "提示词";
        }
        if (SOURCE_SEMANTIC_RECALL.equals(source)) {
            return "向量";
        }
        if (SOURCE_INTENT.equals(source)) {
            return "意图";
        }
        if (SOURCE_PLAN.equals(source)) {
            return "计划";
        }
        if (SOURCE_VALIDATION.equals(source)) {
            return "校验";
        }
        return source == null || source.trim().isEmpty() ? "未知" : source.trim();
    }
}
