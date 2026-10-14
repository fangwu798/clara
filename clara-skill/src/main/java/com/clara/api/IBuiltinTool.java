package com.clara.api;

import com.clara.skill.tool.builtin.BuiltinToolExecutor;

/**
 * 进程内 Java 内置工具：以平台内部能力（而非外部脚本进程）完成的一次工具调用。
 *
 * <p>是什么、在哪一层：clara-skill 的「工具执行」抽象之一，与脚本执行器平级。运行类型为
 * {@link com.clara.enums.skill.ScriptTypeEnum#BUILTIN}，由 {@link BuiltinToolExecutor} 按名称分派。
 *
 * <p>谁创建谁调用：由宿主（装配层）实现并在 {@link BuiltinToolExecutor} 注册，工具往返链路在运行期按
 * 工具方法声明的名称查表调用。失败语义：{@link #execute(String)} 抛出异常即视为本次工具调用失败。
 */
public interface IBuiltinTool {

    /**
     * 内置工具名称，与工具方法文件中的 {@code name} / {@code script} 对应。
     *
     * @return 工具名，非空白
     */
    String name();

    /**
     * 执行工具并返回结果文本（通常为 JSON 文本）。
     *
     * @param argumentsJson 入参 JSON 字符串，可为 null
     * @return 结果文本，非 null
     */
    String execute(String argumentsJson);
}
