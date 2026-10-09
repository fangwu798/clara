package com.clara.skill.tool.script;

import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.skill.tool.ScriptExecutionSpec;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * JavaScript 脚本执行器：通过独立进程运行 JavaScript 脚本，解释器路径取环境变量 {@code NODE_PATH}，
 * 未配置时回退为 {@code node}。
 */
public final class JavaScriptScriptExecutor extends AbstractScriptExecutor {

    @Override
    public ScriptTypeEnum supportedType() {
        return ScriptTypeEnum.JAVASCRIPT;
    }

    @Override
    protected String extension() {
        return ".js";
    }

    @Override
    protected List<String> buildCommand(ScriptExecutionSpec spec, Path scriptFile) {
        return Arrays.asList(nodePath(), scriptFile.toString());
    }

    private String nodePath() {
        String configured = System.getenv("NODE_PATH");
        return configured == null || configured.trim().isEmpty() ? "node" : configured;
    }
}
