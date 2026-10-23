package com.clara.tool;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link ToolInvocationPurpose} 的单元测试：用途说明按「模型声明的 purpose → run_command 实际命令推断 →
 * 工具声明描述」逐级取值，保证同一声明覆盖多种用法时也能说清「这次调用是为了什么」。
 */
public class ToolInvocationPurposeTest {

    private static final String DECLARED = "运行本地命令并返回退出码、标准输出与标准错误。";

    @Test
    public void declaredPurposeInArgumentsWins() {
        String purpose = ToolInvocationPurpose.describe("run_command",
                "{\"purpose\":\"编译 Java 源代码\",\"argv\":[\"javac\",\"-d\",\".\"]}", DECLARED);

        assertEquals("编译 Java 源代码", purpose);
    }

    @Test
    public void infersPurposeFromArgv() {
        String purpose = ToolInvocationPurpose.describe("run_command",
                "{\"argv\":[\"javac\",\"-d\",\"build\",\"src/Main.java\"]}", DECLARED);

        assertEquals("编译 Java 源代码", purpose);
    }

    @Test
    public void infersPurposeFromCommandLine() {
        String purpose = ToolInvocationPurpose.describe("run_command",
                "{\"command\":\"git status --short\"}", DECLARED);

        assertEquals("执行 Git 版本控制操作", purpose);
    }

    @Test
    public void infersPurposeFromAbsoluteExecutablePath() {
        String purpose = ToolInvocationPurpose.describe("run_command",
                "{\"argv\":[\"C:\\\\Program Files\\\\Java\\\\bin\\\\javac.exe\",\"-d\",\".\"]}", DECLARED);

        assertEquals("编译 Java 源代码", purpose);
    }

    @Test
    public void fallsBackToCommandTextWhenCommandUnknown() {
        String purpose = ToolInvocationPurpose.describe("run_command",
                "{\"argv\":[\"my-tool\",\"--flag\"]}", DECLARED);

        assertEquals("my-tool --flag", purpose);
    }

    @Test
    public void fallsBackToDeclaredDescriptionWhenNoCommandGiven() {
        assertEquals(DECLARED, ToolInvocationPurpose.describe("run_command", "{\"cwd\":\"@repo\"}", DECLARED));
    }

    @Test
    public void usesDeclaredDescriptionForOtherTools() {
        String declared = "对指定目录下的文件执行新增 / 读取 / 替换等操作。";

        assertEquals(declared, ToolInvocationPurpose.describe("file_ops",
                "{\"op\":\"read\",\"path\":\"a.txt\"}", declared));
    }

    @Test
    public void exposesFullCommandForConsoleHeader() {
        String quote = String.valueOf((char) 34);
        String arguments = "{" + quote + "argv" + quote + ":[" + quote + "mvn" + quote + ","
                + quote + "-v" + quote + "]}";

        assertEquals("mvn -v", ToolInvocationPurpose.displayCommand("run_command", arguments));
        assertEquals(null, ToolInvocationPurpose.displayCommand("file_ops", arguments));
    }

    @Test
    public void toleratesUnparsableArguments() {
        assertEquals(DECLARED, ToolInvocationPurpose.describe("run_command", "not-json", DECLARED));
        assertEquals(DECLARED, ToolInvocationPurpose.describe("run_command", null, DECLARED));
    }

    @Test
    public void infersWorkflowPurposeFromWorkflowArgument() {
        String declared = "运行一个已配置的工作流，一次调用即可完成多步骤任务。";

        assertEquals("运行工作流 image_generation：文生图，按主题生成图片",
                ToolInvocationPurpose.describe("run_workflow",
                        "{\"workflow\":\"image_generation\",\"inputs\":{\"subject\":\"猫\"}}", declared));
    }

    @Test
    public void echoesUnknownWorkflowId() {
        String declared = "运行一个已配置的工作流，一次调用即可完成多步骤任务。";

        assertEquals("运行工作流 my_custom_flow",
                ToolInvocationPurpose.describe("run_workflow", "{\"workflow\":\"my_custom_flow\"}", declared));
    }

    @Test
    public void declaredPurposeWinsForWorkflow() {
        String declared = "运行一个已配置的工作流，一次调用即可完成多步骤任务。";

        assertEquals("生成一张头像", ToolInvocationPurpose.describe("run_workflow",
                "{\"purpose\":\"生成一张头像\",\"workflow\":\"image_generation\"}", declared));
    }
}
