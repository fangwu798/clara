package com.clara.adapter.console;

import com.clara.usage.TokenCountFormatter;
import com.clara.utils.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量趋势图：记录每轮对话结束时的累计 token 用量，并在控制台用「坐标轴 + 点」渲染总量趋势，
 * 同时按模型输出彩色趋势行。
 *
 * <p>是什么、在哪一层：application 的显示适配层（{@code com.clara.adapter.console}）。只负责「记录采样点 +
 * 文本渲染」，不参与 token 统计口径本身。
 *
 * <p>图形口径：
 * <ul>
 *   <li>上半部分是全部平台累计总量：横轴为时间，纵轴为累计 token，采样点用 {@code *} 标记；</li>
 *   <li>下半部分按模型各画一行彩色趋势：同一个模型在统计表和趋势图中共用同一种颜色，总量固定为亮白色；</li>
 *   <li>趋势行的右侧数字是该系列当前累计值，便于直接读数。</li>
 * </ul>
 *
 * <p>生命周期：数据是会话级的，进程重启或 {@link #reset()}（如 {@code /new}）后清空；线程安全（同步）。
 */
public final class UsageTrendChart {

    /**
     * 时间轴标签格式。
     */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 绘图区宽度（字符数）。
     */
    private static final int WIDTH = 56;

    /**
     * 绘图区高度（行数）。
     */
    private static final int HEIGHT = 9;

    /**
     * 总量曲线采样点字符。
     */
    private static final char POINT = '*';

    /**
     * 模型趋势行的标签最大宽度；超长模型名截断，避免把整张图撑开。
     */
    private static final int LABEL_MAX_WIDTH = 40;

    /**
     * 模型趋势行的等级字符：从低到高，全部使用单列 ASCII，保证与时间轴对齐。
     */
    private static final char[] SPARK = {'_', '.', ':', '-', '=', '+', '*', '@'};

    /**
     * 模型趋势区标题。
     */
    private static final String MODEL_TITLE = "各模型累计 token 趋势（颜色区分模型，右侧为当前累计）";

    /**
     * 采样点列表，按时间先后追加。
     */
    private final List<UsageTrendSample> samples = new ArrayList<>();

    /**
     * 累计 token（自会话开始）；旧采样方法没有绝对总量时用它累加。
     */
    private long cumulative;

    /**
     * 记录一次采样：把本轮 token 累加到累计值并以当前时刻打点。
     *
     * @param turnTokens 本轮 token 消耗，负数按 0 处理
     */
    public synchronized void addSample(long turnTokens) {
        cumulative += Math.max(0L, turnTokens);
        samples.add(new UsageTrendSample(System.currentTimeMillis(), cumulative,
                Collections.<String, Long>emptyMap()));
    }

    /**
     * 记录一次绝对累计值采样：趋势图直接采用「全部平台累计 token」，不会把每轮最后一次调用的用量
     * 重复累加，也不会漏掉一次对话内的多轮工具调用。
     *
     * @param totalTokens 自会话开始的全部平台累计 token，负数按 0 处理
     */
    public synchronized void addCumulative(long totalTokens) {
        cumulative = Math.max(cumulative, Math.max(0L, totalTokens));
        samples.add(new UsageTrendSample(System.currentTimeMillis(), cumulative,
                Collections.<String, Long>emptyMap()));
    }

    /**
     * 记录一次带模型明细的绝对累计值采样。
     *
     * @param modelTokens 模型标识到累计 token 的映射，可为 null（视为无明细）
     * @param totalTokens 自会话开始的全部平台累计 token，负数按 0 处理
     */
    public synchronized void addSnapshot(Map<String, Long> modelTokens, long totalTokens) {
        addSnapshotAt(System.currentTimeMillis(), modelTokens, totalTokens);
    }

    /**
     * 以指定时刻记录采样点；供单测固定横轴位置，避免同一毫秒内多次采样重叠。
     *
     * @param timestampMillis 采样时刻，毫秒时间戳
     * @param modelTokens     模型标识到累计 token 的映射，可为 null（视为无明细）
     * @param totalTokens     自会话开始的全部平台累计 token，负数按 0 处理
     */
    synchronized void addSnapshotAt(long timestampMillis, Map<String, Long> modelTokens, long totalTokens) {
        cumulative = Math.max(cumulative, Math.max(0L, totalTokens));
        samples.add(new UsageTrendSample(timestampMillis, cumulative, modelTokens));
    }

    /**
     * 清空趋势数据与累计值。
     */
    public synchronized void reset() {
        samples.clear();
        cumulative = 0L;
    }

    /**
     * 渲染趋势图（不带颜色）。
     *
     * @return 多行文本（含总量坐标轴、模型趋势行）；无数据时返回一行提示，非 null
     */
    public synchronized String render() {
        return render(false);
    }

    /**
     * 渲染趋势图。
     *
     * @param colorsEnabled 是否使用 ANSI 颜色区分模型
     * @return 多行文本（含总量坐标轴、模型趋势行）；无数据时返回一行提示，非 null
     */
    public synchronized String render(boolean colorsEnabled) {
        if (samples.isEmpty()) {
            return "（暂无趋势数据：完成至少一轮对话后可见）";
        }
        StringBuilder out = new StringBuilder();
        renderTotalChart(out);
        renderModelTrends(out, colorsEnabled);
        return out.toString();
    }

    /**
     * 渲染全部平台累计总量的坐标轴图。
     *
     * @param out 输出缓冲，非 null
     */
    private void renderTotalChart(StringBuilder out) {
        long startTime = samples.get(0).getTimestampMillis();
        long endTime = samples.get(samples.size() - 1).getTimestampMillis();
        long maxY = 1L;
        for (UsageTrendSample sample : samples) {
            maxY = Math.max(maxY, sample.getTotalTokens());
        }

        char[][] grid = new char[HEIGHT][WIDTH];
        for (char[] row : grid) {
            Arrays.fill(row, ' ');
        }
        for (UsageTrendSample sample : samples) {
            int column = columnOf(sample.getTimestampMillis(), startTime, endTime);
            int row = rowOf(sample.getTotalTokens(), maxY);
            grid[row][column] = POINT;
        }

        String maxLabel = TokenCountFormatter.format(maxY);
        int gutter = StringUtils.displayWidth(maxLabel);
        String leftPad = repeat(' ', gutter);
        String startLabel = time(startTime);
        String endLabel = time(endTime);

        out.append("总 token 消耗趋势（横轴=时间，纵轴=全部平台累计用量）\n");
        for (int row = 0; row < HEIGHT; row++) {
            String label;
            if (row == 0) {
                label = maxLabel;
            } else if (row == HEIGHT - 1) {
                label = "0";
            } else {
                label = "";
            }
            out.append(padLeft(label, gutter)).append(" |");
            out.append(grid[row], 0, WIDTH).append('\n');
        }
        out.append(leftPad).append(" +").append(repeat('-', WIDTH)).append('\n');
        int gap = Math.max(1, WIDTH + 2 - startLabel.length() - endLabel.length());
        out.append(leftPad).append("  ").append(startLabel).append(repeat(' ', gap)).append(endLabel).append('\n');
        out.append("共 ").append(samples.size()).append(" 个采样点，末次累计 ")
                .append(TokenCountFormatter.format(samples.get(samples.size() - 1).getTotalTokens()))
                .append(" tokens（全部平台）");
    }

    /**
     * 渲染各模型彩色趋势行；没有模型明细时保持旧版单图输出。
     *
     * @param out           输出缓冲，非 null
     * @param colorsEnabled 是否使用 ANSI 颜色
     */
    private void renderModelTrends(StringBuilder out, boolean colorsEnabled) {
        Map<String, List<Long>> modelSeries = modelSeries();
        if (modelSeries.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(modelSeries.keySet());
        Collections.sort(keys);
        int labelWidth = labelWidth(keys);
        out.append('\n').append(MODEL_TITLE).append('\n');
        appendSparkline(out, "总量", totalSeries(), labelWidth, ConsoleView.ANSI_WHITE, colorsEnabled);
        int colorIndex = 0;
        for (String key : keys) {
            appendSparkline(out, key, modelSeries.get(key), labelWidth,
                    UsageColorPalette.colorFor(colorIndex), colorsEnabled);
            colorIndex++;
        }
        long startTime = samples.get(0).getTimestampMillis();
        long endTime = samples.get(samples.size() - 1).getTimestampMillis();
        String startLabel = time(startTime);
        String endLabel = time(endTime);
        out.append(repeat(' ', labelWidth + 3)).append(startLabel)
                .append(repeat(' ', Math.max(1, WIDTH - startLabel.length() - endLabel.length())))
                .append(endLabel).append('\n');
    }

    /**
     * 取总量系列的逐采样累计值。
     *
     * @return 与采样点一一对应的值列表，非 null
     */
    private List<Long> totalSeries() {
        List<Long> values = new ArrayList<>();
        for (UsageTrendSample sample : samples) {
            values.add(sample.getTotalTokens());
        }
        return values;
    }

    /**
     * 归集各模型的累计值序列：某次采样缺少该模型时沿用上一次的累计值。
     *
     * @return 模型到逐采样值的映射，非 null；无任何模型明细时为空映射
     */
    private Map<String, List<Long>> modelSeries() {
        Map<String, List<Long>> series = new LinkedHashMap<>();
        for (UsageTrendSample sample : samples) {
            for (String key : sample.getModelTokens().keySet()) {
                if (!series.containsKey(key)) {
                    series.put(key, new ArrayList<Long>());
                }
            }
        }
        for (Map.Entry<String, List<Long>> entry : series.entrySet()) {
            long last = 0L;
            for (UsageTrendSample sample : samples) {
                Long value = sample.getModelTokens().get(entry.getKey());
                if (value != null) {
                    last = Math.max(last, value);
                }
                entry.getValue().add(last);
            }
        }
        return series;
    }

    /**
     * 追加一行彩色趋势：标签、趋势字符与右侧当前累计值共用同一颜色。
     *
     * @param out           输出缓冲，非 null
     * @param label         系列名称，非 null
     * @param values        逐采样累计值，非 null
     * @param labelWidth    标签列宽
     * @param color         ANSI 颜色码，非 null
     * @param colorsEnabled 是否启用颜色
     */
    private void appendSparkline(StringBuilder out, String label, List<Long> values, int labelWidth,
                                 String color, boolean colorsEnabled) {
        long max = 1L;
        for (Long value : values) {
            max = Math.max(max, value == null ? 0L : value);
        }
        long startTime = samples.get(0).getTimestampMillis();
        long endTime = samples.get(samples.size() - 1).getTimestampMillis();
        char[] line = new char[WIDTH];
        Arrays.fill(line, ' ');
        for (int index = 0; index < values.size(); index++) {
            long value = values.get(index) == null ? 0L : values.get(index);
            int level = (int) Math.round((double) value / max * (SPARK.length - 1));
            char mark = SPARK[Math.max(0, Math.min(SPARK.length - 1, level))];
            int column = columnOf(samples.get(index).getTimestampMillis(), startTime, endTime);
            if (line[column] == ' ' || mark > line[column]) {
                line[column] = mark;
            }
        }
        long last = values.isEmpty() ? 0L : (values.get(values.size() - 1) == null
                ? 0L : values.get(values.size() - 1));
        String fittedLabel = StringUtils.padRight(fitLabel(label, labelWidth), labelWidth);
        out.append("  ").append(UsageColorPalette.colorize(color, fittedLabel, colorsEnabled))
                .append(' ').append(UsageColorPalette.colorize(color, new String(line), colorsEnabled))
                .append("  ").append(UsageColorPalette.colorize(color,
                        TokenCountFormatter.format(last) + " tokens", colorsEnabled)).append('\n');
    }

    /**
     * 把时间戳映射到横轴列号。
     *
     * @param timestamp 采样时刻
     * @param start     起始时刻
     * @param end       结束时刻
     * @return 列号，范围 {@code [0, WIDTH - 1]}
     */
    private static int columnOf(long timestamp, long start, long end) {
        if (end == start) {
            return 0;
        }
        return (int) Math.round((double) (timestamp - start) / (end - start) * (WIDTH - 1));
    }

    /**
     * 把数值映射到纵轴行号（第一行为最大值）。
     *
     * @param value 当前值
     * @param max   纵轴最大值
     * @return 行号，范围 {@code [0, HEIGHT - 1]}
     */
    private static int rowOf(long value, long max) {
        return HEIGHT - 1 - (int) Math.round((double) value / max * (HEIGHT - 1));
    }

    /**
     * 计算模型趋势行的标签列宽。
     *
     * @param keys 模型标识列表，非 null
     * @return 列宽，不超过 {@value #LABEL_MAX_WIDTH}
     */
    private static int labelWidth(List<String> keys) {
        int width = StringUtils.displayWidth("总量");
        for (String key : keys) {
            width = Math.max(width, StringUtils.displayWidth(key));
        }
        return Math.min(width, LABEL_MAX_WIDTH);
    }

    /**
     * 按显示宽度截断超长标签，末尾保留省略号。
     *
     * @param label 原标签，非 null
     * @param width 最大显示宽度
     * @return 不超过指定宽度的标签，非 null
     */
    private static String fitLabel(String label, int width) {
        if (StringUtils.displayWidth(label) <= width) {
            return label;
        }
        StringBuilder fitted = new StringBuilder();
        int used = 0;
        for (int index = 0; index < label.length(); index++) {
            char value = label.charAt(index);
            int charWidth = value > 0xFF ? 2 : 1;
            if (used + charWidth > width - 1) {
                break;
            }
            fitted.append(value);
            used += charWidth;
        }
        return fitted.append('…').toString();
    }

    /**
     * 把毫秒时间戳格式化成 {@code HH:mm:ss}。
     *
     * @param millis 毫秒时间戳
     * @return 时间文本，非 null
     */
    private static String time(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(TIME);
    }

    /**
     * 按显示宽度左补齐。
     *
     * @param text  文本，可为 null
     * @param width 目标显示宽度
     * @return 补齐后的文本，非 null
     */
    private static String padLeft(String text, int width) {
        String value = text == null ? "" : text;
        int textWidth = StringUtils.displayWidth(value);
        if (textWidth >= width) {
            return value;
        }
        return repeat(' ', width - textWidth) + value;
    }

    /**
     * 重复字符生成文本。
     *
     * @param value 字符
     * @param count 重复次数，负数按 0 处理
     * @return 重复后的文本，非 null
     */
    private static String repeat(char value, int count) {
        char[] buffer = new char[Math.max(0, count)];
        Arrays.fill(buffer, value);
        return new String(buffer);
    }
}
