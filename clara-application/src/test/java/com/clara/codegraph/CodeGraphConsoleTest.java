package com.clara.codegraph;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link CodeGraphConsole#describe(String)} 的渲染测试：把图谱脚本回的那份 JSON 翻成启动日志要用的中文。
 *
 * <p>为什么盯 prepare 的几种动作：启动时的「导入检查」就靠这几句告诉用户「已导入 / 刚发起导入 /
 * 导入失败 / 没导入」，说错了就会把「后台正在建索引」误读成「图谱里没有这个项目」——这类误读会直接
 * 把人带到错误的排查方向上。
 */
public class CodeGraphConsoleTest {

    @Test
    public void alreadyIndexedProjectIsReportedAsReady() {
        String text = CodeGraphConsole.describe("{\"op\":\"prepare\",\"prepared\":true,"
                + "\"indexed\":true,\"ok\":true,\"action\":\"reuse\",\"project\":\"lippo-life\","
                + "\"repo_path\":\"/work/roots/lippo-life\",\"note\":\"当前目录已导入图谱。\"}");

        assertTrue(text.contains("结果：已导入图谱，无需处理"));
        assertTrue(text.contains("项目：lippo-life"));
        assertTrue(text.contains("目录：/work/roots/lippo-life"));
        assertTrue(text.contains("说明：当前目录已导入图谱。"));
    }

    @Test
    public void freshProjectReportsBackgroundImportInsteadOfPlainMiss() {
        // prepared=false + action=indexing：这是「刚发起后台导入」，不是「图谱里没有这个项目」
        String text = CodeGraphConsole.describe("{\"op\":\"prepare\",\"prepared\":false,"
                + "\"action\":\"indexing\",\"ok\":true,\"project\":\"lippo-life\","
                + "\"note\":\"已在后台发起导入\"}");

        assertTrue(text.contains("未导入，已在后台发起导入"));
        assertTrue(text.contains("完成后用 /graph 查看"));
    }

    @Test
    public void failedAndPendingImportsAreDistinguished() {
        String failed = CodeGraphConsole.describe("{\"op\":\"prepare\",\"prepared\":false,"
                + "\"action\":\"index_failed\",\"error\":\"容器看不到该目录\"}");
        assertTrue(failed.contains("结果：未导入，后台发起导入失败"));

        String pending = CodeGraphConsole.describe("{\"op\":\"prepare\",\"prepared\":false,"
                + "\"action\":\"needs_index\"}");
        assertTrue(pending.contains("结果：未导入（本轮读代码仍回退本地文件）"));
    }

    @Test
    public void importFailureKeepsItsReason() {
        String text = CodeGraphConsole.describe("{\"op\":\"index\",\"ok\":false,"
                + "\"error\":\"目录不存在：/work/roots/gone\"}");

        assertTrue(text.contains("结果：失败"));
        assertTrue(text.contains("原因：目录不存在：/work/roots/gone"));
    }

    @Test
    public void nonJsonOutputIsPassedThroughUntouched() {
        assertEquals("[error] 未找到图谱技能脚本", CodeGraphConsole.describe("[error] 未找到图谱技能脚本"));
    }
}
