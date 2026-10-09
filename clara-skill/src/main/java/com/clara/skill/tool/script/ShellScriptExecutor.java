package com.clara.skill.tool.script;

import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.skill.tool.ScriptExecutionSpec;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Shell 脚本执行器：通过独立进程运行 Shell 脚本，取系统 shell（Windows 用 {@code cmd.exe /c}，
 * 其余平台取环境变量 {@code SHELL}，未配置时回退为 {@code sh}）。
 */
public final class ShellScriptExecutor extends AbstractScriptExecutor {

    @Override
    public ScriptTypeEnum supportedType() {
        return ScriptTypeEnum.SHELL;
    }

    @Override
    protected String extension() {
        return ".sh";
    }

    @Override
    protected List<String> buildCommand(ScriptExecutionSpec spec, Path scriptFile) {
        if (isWindows()) {
            return Arrays.asList("cmd.exe", "/c", scriptFile.toString());
        }
        return Arrays.asList(shellPath(), scriptFile.toString());
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String shellPath() {
        String configured = System.getenv("SHELL");
        return configured == null || configured.trim().isEmpty() ? "sh" : configured;
    }
}
