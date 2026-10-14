package com.clara.adapter.console;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 用量趋势图：记录每轮对话结束时的累计 token 用量，并在控制台用「坐标轴 + 点」渲染趋势（不依赖图片）。
 *
 * <p>是什么、在哪一层：application 的显示适配层（{@code com.clara.adapter.console}）。只负责「记录采样点 +
 * 文本渲染」，不参与 token 统计口径本身。
 *
 * <p>图形口径：横轴为时间（各点按时间比例定位，两端标注起止时刻），纵轴为累计总用量（自会话开始累加），
 * 采样点用 {@code *} 标记；由点构成趋势。
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
     * 采样点字符。
     */
    private static final char POINT = '*';

    /**
     * 采样点：{@code [时间戳毫秒, 累计 token]}。
     */
    private final List<long[]> samples = new ArrayList<long[]>();

    /**
     * 累计 token（自会话开始）。
     */
    private long cumulative;

    /**
     * 记录一次采样：把本轮 token 累加到累计值并以当前时刻打点。
     *
     * @param turnTokens 本轮 token 消耗，负数按 0 处理
     */
    public synchronized void record(long turnTokens) {
        cumulative += Math.max(0L, turnTokens);
        samples.add(new long[] {System.currentTimeMillis(), cumulative});
    }

    /**
     * 清空趋势数据与累计值。
     */
    public synchronized void reset() {
        samples.clear();
        cumulative = 0L;
    }

    /**
     * 渲染趋势图。
     *
     * @return 多行文本（含横纵坐标与采样点）；无数据时返回一行提示，非 null
     */
    public synchronized String render() {
        if (samples.isEmpty()) {
            return "（暂无趋势数据：完成至少一轮对话后可见）";
        }
        long startTime = samples.get(0)[0];
        long endTime = samples.get(samples.size() - 1)[0];
        long maxY = 1L;
        for (long[] sample : samples) {
            maxY = Math.max(maxY, sample[1]);
        }

        char[][] grid = new char[HEIGHT][WIDTH];
        for (char[] row : grid) {
            Arrays.fill(row, ' ');
        }
        for (long[] sample : samples) {
            int column = endTime == startTime ? 0
                    : (int) Math.round((double) (sample[0] - startTime) / (endTime - startTime) * (WIDTH - 1));
            int row = HEIGHT - 1 - (int) Math.round((double) sample[1] / maxY * (HEIGHT - 1));
            grid[row][column] = POINT;
        }

        int gutter = Long.toString(maxY).length();
        String leftPad = repeat(' ', gutter);
        String startLabel = time(startTime);
        String endLabel = time(endTime);

        StringBuilder out = new StringBuilder();
        out.append("累计 token 趋势（横轴=时间，纵轴=累计用量）\n");
        for (int row = 0; row < HEIGHT; row++) {
            String label = row == 0 ? Long.toString(maxY) : (row == HEIGHT - 1 ? "0" : "");
            out.append(padLeft(label, gutter)).append(" |");
            out.append(grid[row], 0, WIDTH).append('\n');
        }
        out.append(leftPad).append(" +").append(repeat('-', WIDTH)).append('\n');
        int gap = Math.max(1, WIDTH + 2 - startLabel.length() - endLabel.length());
        out.append(leftPad).append("  ").append(startLabel).append(repeat(' ', gap)).append(endLabel).append('\n');
        out.append("共 ").append(samples.size()).append(" 个采样点，末次累计 ").append(samples.get(samples.size() - 1)[1]).append(" tokens");
        return out.toString();
    }

    private static String time(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(TIME);
    }

    private static String padLeft(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return repeat(' ', width - text.length()) + text;
    }

    private static String repeat(char value, int count) {
        char[] buffer = new char[Math.max(0, count)];
        Arrays.fill(buffer, value);
        return new String(buffer);
    }
}
