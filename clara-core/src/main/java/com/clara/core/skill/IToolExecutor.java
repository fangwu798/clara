package com.clara.core.skill;

/**
 * 工具执行接口：执行工具方法对应的 Python 脚本并返回结果。
 *
 * <p>core 定义的契约接口，由 clara-skill 实现；工具方法统一 Python 执行，独立进程、权限与超时约束。
 */
public interface IToolExecutor {

    /**
     * 执行指定工具方法的 Python 脚本。
     *
     * @param toolName      工具方法名称，不可为空白
     * @param argumentsJson 模型生成的入参 JSON 字符串，可为 null
     * @return 脚本执行结果（通常为 JSON 文本），非 null
     */
    String execute(String toolName, String argumentsJson);
}
