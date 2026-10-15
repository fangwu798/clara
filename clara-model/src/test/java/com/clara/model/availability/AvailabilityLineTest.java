package com.clara.model.availability;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 可用性检查输出行的渲染规则：状态级别映射与按显示宽度对齐的四列。
 */
public class AvailabilityLineTest {

    /**
     * 可用、跳过、不可用三种状态映射到着色所需的级别。
     */
    @Test
    public void mapsResultToLevel() {
        List<AvailabilityLine> lines = AvailabilityLine.render(Arrays.asList(
                ModelCheckResult.available(Paths.get("a.yml"), "a", "OPENAI", "gpt", 12L),
                ModelCheckResult.missingCredential(Paths.get("b.yml"), "b", "CLARA_KIMI_API_KEY"),
                ModelCheckResult.unavailable(Paths.get("c.yml"), "c", "DEEPSEEK", "ds", "denied")));

        assertEquals(AvailabilityLine.Level.OK, lines.get(0).getLevel());
        assertEquals(AvailabilityLine.Level.WARN, lines.get(1).getLevel());
        assertEquals(AvailabilityLine.Level.FAIL, lines.get(2).getLevel());
    }

    /**
     * 别名长度不同、状态标签宽度不同（全角）时，「平台与模型」列与结论列仍从同一列起始。
     */
    @Test
    public void alignsColumnsAcrossRows() {
        List<String> lines = texts(AvailabilityLine.render(Arrays.asList(
                ModelCheckResult.available(Paths.get("a.yml"), "a", "OPENAI", "gpt", 12L),
                ModelCheckResult.unavailable(Paths.get("bb.yml"), "bb", "DEEPSEEK", "ds", "denied"),
                ModelCheckResult.missingCredential(Paths.get("ccc.yml"), "ccc", "CLARA_KIMI_API_KEY"))));

        assertEquals(3, lines.size());
        assertEquals(columnOf(lines.get(0), "provider="), columnOf(lines.get(1), "provider="));
        assertEquals(columnOf(lines.get(0), "首字符响应="), columnOf(lines.get(1), "denied"));
        assertEquals(columnOf(lines.get(0), "首字符响应="), columnOf(lines.get(2), "缺少环境变量"));
    }

    /**
     * 可用状态的补充说明紧跟响应时间，不额外换行。
     */
    @Test
    public void appendsAvailableDetailAfterLatency() {
        List<String> lines = texts(AvailabilityLine.render(Collections.singletonList(
                ModelCheckResult.available(Paths.get("a.yml"), "a", "DEEPSEEK", "ds", 21L, "推理模型仅返回思维链"))));

        assertTrue(lines.get(0).contains("首字符响应=21ms  推理模型仅返回思维链"));
    }

    /**
     * 取出输出行的纯文本。
     *
     * @param lines 输出行
     * @return 与输入一一对应的纯文本列表
     */
    private static List<String> texts(List<AvailabilityLine> lines) {
        return lines.stream().map(AvailabilityLine::getText).collect(Collectors.toList());
    }

    /**
     * 取子串在行内的显示列：东亚全角字符按两列计算。
     *
     * @param line  单行文本
     * @param token 目标子串
     * @return 子串起始位置的显示列
     */
    private static int columnOf(String line, String token) {
        int index = line.indexOf(token);
        int column = 0;
        for (int position = 0; position < index; position++) {
            column += line.charAt(position) < 0x1100 ? 1 : 2;
        }
        return column;
    }
}
