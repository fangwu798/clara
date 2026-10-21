package com.clara.adapter.console;

import org.junit.Test;

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
}
