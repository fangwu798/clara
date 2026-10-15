package com.clara.model.availability;

import com.clara.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 可用性检查输出的单行内容：纯文本与状态级别。
 *
 * <p>是什么、在哪一层：{@code clara-model} 可用性检查包中的不可变值对象。检查只产出「文本 + 级别」，
 * 由调用方决定呈现方式——application 层据此为「可用 / 不可用 / 跳过」着色，本包不感知颜色控制码。
 *
 * <p>约定：{@link #getText()} 始终是纯文本，可直接写入日志文件；{@link #getLevel()} 只表达语义，
 * 与终端是否支持颜色无关。
 *
 * <p>对齐规则：{@link #render(List)} 把同一批结果渲染成「状态、别名、平台与模型、结论」四列，各列宽度
 * 取本批次内的最大显示宽度（由 {@link StringUtils#displayWidth(String)} 按「东亚全角占两列」计算），
 * 因此中文状态标签与英文别名混排时结论列也能对齐。
 */
public final class AvailabilityLine {

    /**
     * 行首缩进，与启动输出的其他条目一致。
     */
    private static final String INDENT = "  ";

    /**
     * 列间分隔。
     */
    private static final String GAP = "  ";

    /**
     * 单行的状态级别，供调用方决定呈现方式。
     */
    public enum Level {

        /** 普通信息行（进度提示、表头）。 */
        INFO,

        /** 检查通过：模型可用。 */
        OK,

        /** 未发起真实请求或未取得正文的中间状态：跳过检查。 */
        WARN,

        /** 检查未通过：模型不可用。 */
        FAIL
    }

    /**
     * 状态级别，非 null。
     */
    private final Level level;

    /**
     * 单行纯文本，非 null。
     */
    private final String text;

    /**
     * 创建单行输出。
     *
     * @param level 状态级别，非 null
     * @param text  单行纯文本，非 null
     */
    private AvailabilityLine(Level level, String text) {
        this.level = level;
        this.text = text;
    }

    /**
     * 创建普通信息行。
     *
     * @param text 单行纯文本，非 null
     * @return 级别为 {@link Level#INFO} 的输出行
     */
    public static AvailabilityLine info(String text) {
        return new AvailabilityLine(Level.INFO, text);
    }

    /**
     * 创建「模型可用」输出行。
     *
     * @param text 单行纯文本，非 null
     * @return 级别为 {@link Level#OK} 的输出行
     */
    public static AvailabilityLine ok(String text) {
        return new AvailabilityLine(Level.OK, text);
    }

    /**
     * 创建「跳过检查」输出行。
     *
     * @param text 单行纯文本，非 null
     * @return 级别为 {@link Level#WARN} 的输出行
     */
    public static AvailabilityLine warn(String text) {
        return new AvailabilityLine(Level.WARN, text);
    }

    /**
     * 创建「模型不可用」输出行。
     *
     * @param text 单行纯文本，非 null
     * @return 级别为 {@link Level#FAIL} 的输出行
     */
    public static AvailabilityLine fail(String text) {
        return new AvailabilityLine(Level.FAIL, text);
    }

    /**
     * 把一批检查结果渲染成按列对齐的输出行，顺序与输入一致。
     *
     * <p>为什么要整批渲染：状态、别名与「平台与模型」三列的宽度随内容变化，只有拿到整批结果才能算出各列
     * 宽度；单个结果独立成行会让不同行的结论互相错位。
     *
     * @param results 检查结果，非 null
     * @return 与输入一一对应的输出行
     */
    public static List<AvailabilityLine> render(List<ModelCheckResult> results) {
        int statusWidth = 0;
        int aliasWidth = 0;
        int identityWidth = 0;
        for (ModelCheckResult result : results) {
            statusWidth = Math.max(statusWidth, StringUtils.displayWidth(status(result)));
            aliasWidth = Math.max(aliasWidth, StringUtils.displayWidth(result.getAlias()));
            identityWidth = Math.max(identityWidth, StringUtils.displayWidth(identity(result)));
        }
        List<AvailabilityLine> lines = new ArrayList<AvailabilityLine>();
        for (ModelCheckResult result : results) {
            StringBuilder text = new StringBuilder(INDENT)
                    .append(StringUtils.padRight(status(result), statusWidth)).append(GAP)
                    .append(StringUtils.padRight(result.getAlias(), aliasWidth)).append(GAP)
                    .append(StringUtils.padRight(identity(result), identityWidth));
            String conclusion = conclusion(result);
            if (!conclusion.isEmpty()) {
                text.append(GAP).append(conclusion);
            }
            lines.add(new AvailabilityLine(level(result), text.toString()));
        }
        return lines;
    }

    /**
     * 读取状态级别。
     *
     * @return 状态级别，非 null
     */
    public Level getLevel() {
        return level;
    }

    /**
     * 读取单行纯文本。
     *
     * @return 文本，非 null
     */
    public String getText() {
        return text;
    }

    /**
     * 取状态标签。
     *
     * @param result 检查结果，非 null
     * @return {@code [可用]}、{@code [跳过]} 或 {@code [不可用]}
     */
    private static String status(ModelCheckResult result) {
        if (result.isAvailable()) {
            return "[可用]";
        }
        return result.isSkipped() ? "[跳过]" : "[不可用]";
    }

    /**
     * 取状态级别。
     *
     * @param result 检查结果，非 null
     * @return 可用为 {@link Level#OK}、跳过为 {@link Level#WARN}、其余为 {@link Level#FAIL}
     */
    private static Level level(ModelCheckResult result) {
        if (result.isAvailable()) {
            return Level.OK;
        }
        return result.isSkipped() ? Level.WARN : Level.FAIL;
    }

    /**
     * 取「平台与模型」列；配置未解析成功（平台或模型缺失）时为空串，该列仍按最大宽度占位以保持对齐。
     *
     * @param result 检查结果，非 null
     * @return 形如 {@code provider=DEEPSEEK model=deepseek-v4-pro} 的文本，或空串
     */
    private static String identity(ModelCheckResult result) {
        return result.getModel() == null
                ? ""
                : "provider=" + result.getProvider() + " model=" + result.getModel();
    }

    /**
     * 取结论列：可用状态给出首字符响应时间，其余状态给出跳过或不可用的原因。
     *
     * @param result 检查结果，非 null
     * @return 结论文本；没有可展示内容时为空串
     */
    private static String conclusion(ModelCheckResult result) {
        if (!result.isAvailable()) {
            return isBlank(result.getDetail()) ? "" : result.getDetail();
        }
        String latency = "首字符响应=" + result.getFirstCharacterMillis() + "ms";
        return isBlank(result.getDetail()) ? latency : latency + GAP + result.getDetail();
    }

    /**
     * 判断字符串是否为 null、空串或只含空白字符。
     *
     * @param value 待检查字符串
     * @return 字符串没有有效内容时返回 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
