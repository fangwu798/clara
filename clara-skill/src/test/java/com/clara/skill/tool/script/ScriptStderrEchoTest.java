package com.clara.skill.tool.script;

import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.skill.tool.ScriptExecutionSpec;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 脚本标准错误实时回显的单元测试：脚本 stderr 逐行进入 {@code System.out}（控制台与运行日志的合流），
 * 而脚本结果仍只从 stdout 读取，二者互不污染。
 */
public class ScriptStderrEchoTest {

    /** 写两行标准错误并输出一行 JSON 结果的内联脚本。 */
    private static final String SCRIPT =
            "import sys\n"
                    + "sys.stderr.write('progress-1\\n')\n"
                    + "sys.stderr.write('progress-2\\n')\n"
                    + "print('{\"ok\":true}')\n";

    @Test
    public void echoesStderrLinesToSystemOut() throws Exception {
        Assume.assumeTrue("未安装 Python，跳过脚本回显测试", pythonAvailable());

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        String result;
        try {
            System.setOut(new PrintStream(captured, true, "UTF-8"));
            result = new PythonScriptExecutor().execute(new ScriptExecutionSpec(
                    ScriptTypeEnum.PYTHON, SCRIPT, "{}", 30_000L));
        } finally {
            System.setOut(original);
        }

        assertEquals("{\"ok\":true}", result);
        String echoed = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertTrue("应回显 stderr 行，实际：" + echoed, echoed.contains("[tool] progress-1"));
        assertTrue("应回显 stderr 行，实际：" + echoed, echoed.contains("[tool] progress-2"));
    }

    private static boolean pythonAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder(PythonScriptExecutor.pythonPath(), "--version").start();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
