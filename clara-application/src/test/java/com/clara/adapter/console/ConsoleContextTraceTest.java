package com.clara.adapter.console;

import com.clara.utils.StringUtils;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * {@link ConsoleContextTrace} 的排版断言：状态列对齐、明细缩进、未知来源兜底。
 *
 * <p>为什么盯着空格：标签大多是中文（全角占两列），字符数与显示宽度不等，
 * 靠肉眼看终端只会觉得「没对齐」而不会报错，所以这里按显示宽度断言。
 */
public class ConsoleContextTraceTest {

    @Test
    public void statusColumnAlignsAcrossSources() {
        String graph = ConsoleContextTrace.lines("code_graph", 1, 320,
                Collections.singletonList("pattern=X")).get(0);
        String prompt = ConsoleContextTrace.lines("prompt_match", 2, 33, Collections.emptyList()).get(0);
        String recall = ConsoleContextTrace.lines("semantic_recall", 0, 8, Collections.emptyList()).get(0);

        assertEquals("  [图谱]    命中 1 条（320ms）", graph);
        assertEquals("  [提示词]  命中 2 条（33ms）", prompt);
        assertEquals("  [向量]    未命中（8ms）", recall);
        assertEquals(statusColumn(graph), statusColumn(prompt));
        assertEquals(statusColumn(prompt), statusColumn(recall));
    }

    @Test
    public void detailsAreIndentedUnderStatusColumn() {
        List<String> lines = ConsoleContextTrace.lines("code_graph", 1, 12,
                Arrays.asList("pattern=X", "  ", null));

        assertEquals(2, lines.size());
        assertEquals("  [图谱]    命中 1 条（12ms）", lines.get(0));
        assertEquals("            pattern=X", lines.get(1));
    }

    @Test
    public void unknownSourceKeepsItsName() {
        assertEquals("  [custom]  未命中（0ms）",
                ConsoleContextTrace.lines("custom", 0, 0, null).get(0));
        assertEquals("  [未知]    未命中（0ms）",
                ConsoleContextTrace.lines(null, 0, 0, null).get(0));
    }

    @Test
    public void phaseLinesAlignWithHitLines() {
        String intent = ConsoleContextTrace.phaseLines("intent", "复杂任务", 860,
                Collections.singletonList("依据：跨多个文件")).get(0);
        String validation = ConsoleContextTrace.phaseLines("validation", "通过", 6, null).get(0);
        String recall = ConsoleContextTrace.lines("semantic_recall", 0, 8, Collections.emptyList()).get(0);

        assertEquals("  [意图]    复杂任务（860ms）", intent);
        assertEquals("  [校验]    通过（6ms）", validation);
        assertEquals(columnOf(recall, "未命中"), columnOf(intent, "复杂任务"));
        assertEquals(columnOf(intent, "复杂任务"), columnOf(validation, "通过"));
    }

    @Test
    public void phaseDetailsAreIndentedAndBlankStatusFallsBack() {
        List<String> lines = ConsoleContextTrace.phaseLines("plan", "  ", 3,
                Collections.singletonList("[s1] 读取代码"));

        assertEquals("  [计划]    进行中（3ms）", lines.get(0));
        assertEquals("            [s1] 读取代码", lines.get(1));
    }

    /** 指定标记之前的显示宽度，用于断言不同来源的状态列起点一致。 */
    private static int columnOf(String line, String marker) {
        return StringUtils.displayWidth(line.substring(0, line.indexOf(marker)));
    }

    /** 状态列起点：状态文本之前那段的显示宽度，不同来源应一致。 */
    private static int statusColumn(String line) {
        int at = line.contains("未命中") ? line.indexOf("未命中") : line.indexOf("命中");
        return StringUtils.displayWidth(line.substring(0, at));
    }
}
