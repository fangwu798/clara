package com.clara.adapter.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConsoleView#briefToolPurpose(String)} 的单元测试：把为模型编写的长工具描述压成一行短句。
 */
public class ConsoleViewToolPurposeTest {

    @Test
    public void keepsOnlyFirstSentence() {
        String description = "运行一个已配置的工作流，一次调用即可完成多步骤任务。可用工作流：analyze_repo_docs 等。";

        assertEquals("运行一个已配置的工作流，一次调用即可完成多步骤任务。",
                ConsoleView.briefToolPurpose(description));
    }

    @Test
    public void collapsesWhitespaceAndNewlines() {
        assertEquals("执行查询 并返回结果。", ConsoleView.briefToolPurpose("  执行查询\n  并返回结果。 "));
    }

    @Test
    public void truncatesLongSentenceWithEllipsis() {
        StringBuilder longText = new StringBuilder();
        for (int index = 0; index < 100; index++) {
            longText.append('a');
        }

        String brief = ConsoleView.briefToolPurpose(longText.toString());

        assertEquals(81, brief.length());
        assertTrue(brief.endsWith("…"));
    }

    @Test
    public void returnsEmptyWhenDescriptionMissing() {
        assertEquals("", ConsoleView.briefToolPurpose(null));
        assertEquals("", ConsoleView.briefToolPurpose("   "));
    }
}
