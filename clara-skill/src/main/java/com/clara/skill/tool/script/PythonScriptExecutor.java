package com.clara.skill.tool.script;

import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.skill.tool.ScriptExecutionSpec;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Python 脚本执行器：通过独立进程运行 Python 脚本，解释器路径取环境变量 {@code PYTHON_PATH}，
 * 未配置时回退为 {@code python}。
 */
public final class PythonScriptExecutor extends AbstractScriptExecutor {

    @Override
    public ScriptTypeEnum supportedType() {
        return ScriptTypeEnum.PYTHON;
    }

    @Override
    protected String extension() {
        return ".py";
    }

    @Override
    protected List<String> buildCommand(ScriptExecutionSpec spec, Path scriptFile) {
        return Arrays.asList(pythonPath(), scriptFile.toString());
    }

    private String pythonPath() {
        String configured = System.getenv("PYTHON_PATH");
        return configured == null || configured.trim().isEmpty() ? "python" : configured;
    }
}
