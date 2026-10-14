package com.clara.skill.tool;

import com.clara.api.IScriptExecutor;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.exception.ClaraException;
import com.clara.skill.SkillErrors;
import com.clara.skill.tool.script.JavaScriptScriptExecutor;
import com.clara.skill.tool.script.PythonScriptExecutor;
import com.clara.skill.tool.script.ShellScriptExecutor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行类型 → 执行器 的注册与查找表。
 *
 * <p>默认注册 Python / JavaScript / Shell 三种执行器；可通过 {@link #register(IScriptExecutor)}
 * 扩展或覆盖，调度逻辑按运行类型查找，不在调度处写死脚本类型。
 */
public final class ScriptExecutorRegistry {

    private final Map<ScriptTypeEnum, IScriptExecutor> executors = new ConcurrentHashMap<>();

    /**
     * 创建注册表并注册三种默认脚本执行器。
     */
    public ScriptExecutorRegistry() {
        register(new PythonScriptExecutor());
        register(new JavaScriptScriptExecutor());
        register(new ShellScriptExecutor());
    }

    /**
     * 注册一个脚本执行器，同类型重复注册时后者覆盖前者。
     *
     * @param executor 执行器，不可为 null，且 {@code supportedType()} 不可为 null
     * @throws IllegalArgumentException executor 或 supportedType 为 null 时
     */
    public void register(IScriptExecutor executor) {
        if (executor == null || executor.supportedType() == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        executors.put(executor.supportedType(), executor);
    }

    /**
     * 按运行类型查找执行器。
     *
     * @param type 脚本运行类型，可为 null
     * @return 对应执行器，非 null
     * @throws ClaraException 未注册对应执行器时
     */
    public IScriptExecutor resolve(ScriptTypeEnum type) {
        IScriptExecutor executor = type == null ? null : executors.get(type);
        if (executor == null) {
            throw SkillErrors.handlerNotFound(type);
        }
        return executor;
    }
}
