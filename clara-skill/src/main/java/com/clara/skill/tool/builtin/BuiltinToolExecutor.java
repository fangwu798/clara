package com.clara.skill.tool.builtin;

import com.clara.api.IBuiltinTool;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.skill.SkillErrors;
import com.clara.api.IScriptExecutor;
import com.clara.skill.tool.ScriptExecutionSpec;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置工具执行器：把「运行类型为 BUILTIN」的工具方法分派到进程内 Java 内置工具。
 *
 * <p>是什么、在哪一层：clara-skill 内 {@link IScriptExecutor} 的实现，与 Python / JavaScript / Shell
 * 脚本执行器同处工具执行层，经 {@code ScriptExecutorRegistry} 按运行类型查找。
 *
 * <p>谁创建谁调用：由装配层（application）构造并注册进执行器注册表；工具往返链路在运行期按
 * {@link ScriptExecutionSpec#getScript()}（约定为内置工具名）查表调用。
 *
 * <p>可扩展性：内置工具按名称注册，新增内置工具只需 {@link #register(IBuiltinTool)}，不必改动分派逻辑。
 * 失败语义：未注册的内置工具名抛「内置工具未注册」的执行失败异常。
 */
public final class BuiltinToolExecutor implements IScriptExecutor {

    private final Map<String, IBuiltinTool> tools = new ConcurrentHashMap<>();

    /**
     * 以一组内置工具创建执行器。
     *
     * @param tools 内置工具列表，不可为 null
     * @throws IllegalArgumentException tools 为 null，或含 null / 名称为空的工具时
     */
    public BuiltinToolExecutor(List<IBuiltinTool> tools) {
        if (tools == null) {
            throw new IllegalArgumentException("tools must not be null");
        }
        for (IBuiltinTool tool : tools) {
            register(tool);
        }
    }

    /**
     * 注册一个内置工具，同名重复注册时后者覆盖前者。
     *
     * @param tool 内置工具，不可为 null，且 {@link IBuiltinTool#name()} 不可为空白
     * @throws IllegalArgumentException tool 为 null 或名称为空白时
     */
    public void register(IBuiltinTool tool) {
        if (tool == null || tool.name() == null || tool.name().trim().isEmpty()) {
            throw new IllegalArgumentException("builtin tool must not be null or unnamed");
        }
        tools.put(tool.name(), tool);
    }

    @Override
    public ScriptTypeEnum supportedType() {
        return ScriptTypeEnum.BUILTIN;
    }

    @Override
    public String execute(ScriptExecutionSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        IBuiltinTool tool = tools.get(spec.getScript());
        if (tool == null) {
            throw SkillErrors.executionFailed("内置工具未注册：" + spec.getScript());
        }
        return tool.execute(spec.getArgumentsJson());
    }
}
