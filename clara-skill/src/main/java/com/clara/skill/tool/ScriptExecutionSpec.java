package com.clara.skill.tool;

import com.clara.context.CancellationToken;
import com.clara.enums.skill.ScriptTypeEnum;

/**
 * 统一脚本执行描述：一次脚本执行的上下文（运行类型、脚本引用、入参、超时与可选取消信号）。
 *
 * <p>作为 {@link IScriptExecutor} 的入参，屏蔽各运行环境的差异，是调度层与具体执行器之间的公共数据契约。
 */
public final class ScriptExecutionSpec {

    private final ScriptTypeEnum type;
    private final String script;
    private final String argumentsJson;
    private final long timeoutMillis;
    private final CancellationToken cancellationToken;

    /**
     * 创建不带取消信号的执行描述。
     */
    public ScriptExecutionSpec(ScriptTypeEnum type, String script, String argumentsJson, long timeoutMillis) {
        this(type, script, argumentsJson, timeoutMillis, null);
    }

    /**
     * 创建执行描述。
     *
     * @param type              脚本运行类型，不可为 null
     * @param script            脚本引用（文件路径或内联脚本文本），不可为空白
     * @param argumentsJson     入参 JSON 字符串，可为 null
     * @param timeoutMillis     硬超时（毫秒），必须为正
     * @param cancellationToken 可选取消信号，可为 null
     * @throws IllegalArgumentException type 为 null、script 为空白或 timeoutMillis 非正时
     */
    public ScriptExecutionSpec(ScriptTypeEnum type, String script, String argumentsJson, long timeoutMillis,
            CancellationToken cancellationToken) {
        if (type == null || blank(script) || timeoutMillis <= 0L) {
            throw new IllegalArgumentException("script execution spec is invalid");
        }
        this.type = type;
        this.script = script;
        this.argumentsJson = argumentsJson;
        this.timeoutMillis = timeoutMillis;
        this.cancellationToken = cancellationToken;
    }

    public ScriptTypeEnum getType() {
        return type;
    }

    public String getScript() {
        return script;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
