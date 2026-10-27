package com.clara.adapter.console;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link UsageTrendChart} 的坐标轴与累计值展示测试。
 */
public class UsageTrendChartTest {

    /**
     * 趋势图的纵轴上限与末次累计值使用万 / 千万 / 亿格式。
     */
    @Test
    public void rendersFormattedCumulativeTokens() {
        UsageTrendChart chart = new UsageTrendChart();
        chart.addSample(10_000L);
        chart.addSample(2_345L);

        String rendered = chart.render();

        assertTrue(rendered.contains("1.23万"));
        assertTrue(rendered.contains("末次累计 1.23万 tokens"));
    }
    /**
     * 绝对累计采样用于「全部平台总量」趋势：后一次采样覆盖前一次，不做重复累加。
     */
    @Test
    public void rendersAbsoluteCumulativeTotal() {
        UsageTrendChart chart = new UsageTrendChart();
        chart.addCumulative(10_000L);
        chart.addCumulative(12_345L);

        String rendered = chart.render();

        assertTrue(rendered.contains("总 token 消耗趋势"));
        assertTrue(rendered.contains("末次累计 1.23万 tokens（全部平台）"));
    }

    /**
     * 带模型明细的采样同时渲染总量曲线与各模型彩色趋势行；关闭颜色时不输出 ANSI。
     */
    @Test
    public void rendersColoredModelTrendsAlongsideTotal() {
        UsageTrendChart chart = new UsageTrendChart();
        chart.addSnapshotAt(1_000L, modelUsage(1_000L, 2_000L), 3_000L);
        chart.addSnapshotAt(2_000L, modelUsage(5_000L, 7_000L), 12_000L);

        String plain = chart.render();
        assertTrue(plain.contains("各模型累计 token 趋势"));
        assertTrue(plain.contains("DEEPSEEK/deepseek-v4-pro"));
        assertTrue(plain.contains("OPENAI/gpt-5.6-sol"));
        assertTrue(plain.contains("总量"));
        assertTrue(plain.contains("5000 tokens"));
        assertTrue(plain.contains("1.20万 tokens"));
        assertFalse(plain.contains(ConsoleView.ANSI_CYAN));
        assertFalse(plain.contains(ConsoleView.ANSI_WHITE));

        String colored = chart.render(true);
        assertTrue(colored.contains(ConsoleView.ANSI_CYAN));
        assertTrue(colored.contains(ConsoleView.ANSI_WHITE));
    }

    private static Map<String, Long> modelUsage(long deepseek, long openai) {
        Map<String, Long> usage = new LinkedHashMap<>();
        usage.put("DEEPSEEK/deepseek-v4-pro", deepseek);
        usage.put("OPENAI/gpt-5.6-sol", openai);
        return usage;
    }
}
