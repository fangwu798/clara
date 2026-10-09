package com.clara.skill.tool.script;

import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.skill.tool.ScriptExecutionSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

/**
 * {@link ShellScriptExecutor} 的单元测试：用系统 shell 跑通 echo 脚本并返回结果。
 */
public class ShellScriptExecutorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void shellExecutorRunsEchoScript() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script;
        if (windows) {
            script = tempFolder.getRoot().toPath().resolve("echo.cmd");
            Files.write(script, ("@echo off\r\necho {\"result\":\"ok\"}\r\n").getBytes(StandardCharsets.UTF_8));
        } else {
            script = tempFolder.getRoot().toPath().resolve("echo.sh");
            Files.write(script, ("#!/bin/sh\necho '{\"result\":\"ok\"}'\n").getBytes(StandardCharsets.UTF_8));
        }
        ShellScriptExecutor executor = new ShellScriptExecutor();
        ScriptExecutionSpec spec = new ScriptExecutionSpec(ScriptTypeEnum.SHELL, script.toString(), "{}", 10_000L);
        assertEquals("{\"result\":\"ok\"}", executor.execute(spec).trim());
    }
}
