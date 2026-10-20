package com.clara.codegraph;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link CodeGraphConsole#describe(String)} 的补充渲染测试：空输出、普通导入结果、
 * prepare 未带动作，以及目录 / 提示字段的取值优先级。
 */
public class CodeGraphConsoleRenderingTest {

    @Test
    public void emptyOrNullOutputGivesActionableWarning() {
        String nullText = CodeGraphConsole.describe(null);
        String blankText = CodeGraphConsole.describe("   ");

        assertEquals("[warn] 图谱没有返回任何内容（可能没装 Python，或图谱二进制不可用）。", nullText);
        assertEquals(nullText, blankText);
    }

    @Test
    public void indexResultUsesOkFlag() {
        assertTrue(CodeGraphConsole.describe("{\"op\":\"index\",\"ok\":true}").contains("结果：已发起导入"));
        assertTrue(CodeGraphConsole.describe("{\"op\":\"index\",\"ok\":false}").contains("结果：导入失败"));
    }

    @Test
    public void prepareWithoutActionFallsBackToPreparedFlag() {
        String ready = CodeGraphConsole.describe("{\"op\":\"prepare\",\"prepared\":true}");
        String pending = CodeGraphConsole.describe("{\"op\":\"prepare\",\"prepared\":false}");
        String unknownAction = CodeGraphConsole.describe(
                "{\"op\":\"prepare\",\"action\":\"mystery\",\"prepared\":true}");

        assertTrue(ready.contains("结果：已导入图谱，无需处理"));
        assertTrue(pending.contains("结果：未导入"));
        assertTrue(unknownAction.contains("结果：已导入图谱，无需处理"));
    }

    @Test
    public void hostRepoPathWinsOverRepoPathAndHintIsRendered() {
        String text = CodeGraphConsole.describe("{\"op\":\"index\",\"ok\":true,"
                + "\"repo_path\":\"/container/repo\",\"host_repo_path\":\"E:/workspace/project\","
                + "\"project\":\"proj\",\"note\":\"已导入\",\"hint\":\"用 /graph 查看\"}");

        assertTrue(text.contains("目录：E:/workspace/project"));
        assertTrue(!text.contains("/container/repo"));
        assertTrue(text.contains("说明：已导入"));
        assertTrue(text.contains("提示：用 /graph 查看"));
    }

    @Test
    public void repoPathIsUsedWhenHostPathAbsent() {
        String text = CodeGraphConsole.describe("{\"op\":\"index\",\"ok\":true,\"repo_path\":\"/work/repo\"}");

        assertTrue(text.contains("目录：/work/repo"));
    }

    @Test
    public void nullFieldsAreSkipped() {
        String text = CodeGraphConsole.describe("{\"op\":\"index\",\"ok\":true,"
                + "\"host_repo_path\":null,\"repo_path\":\"  \",\"note\":null,\"hint\":\"\"}");

        assertTrue(text.contains("结果：已发起导入"));
        assertTrue(!text.contains("目录："));
        assertTrue(!text.contains("说明："));
        assertTrue(!text.contains("提示："));
    }
}