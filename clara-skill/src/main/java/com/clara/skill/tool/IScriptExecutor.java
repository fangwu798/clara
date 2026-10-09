package com.clara.skill.tool;

import com.clara.enums.skill.ScriptTypeEnum;

/**
 * 脚本执行器内部抽象：定义「运行环境 → 执行脚本 → 返回结果」的统一约定。
 *
 * <p>各运行类型（Python / JavaScript / Shell）的实现类实现本接口并在 {@link ScriptExecutorRegistry}
 * 注册；新增脚本类型只需新增实现，无需改动统一调度逻辑。
 */
public interface IScriptExecutor {

    /**
     * 本执行器支持的脚本运行类型。
     *
     * @return 运行类型，非 null
     */
    ScriptTypeEnum supportedType();

    /**
     * 执行脚本并返回 stdout 结果文本（通常为 JSON 文本）。
     *
     * @param spec 统一执行描述，不可为 null
     * @return stdout 结果文本，非 null
     */
    String execute(ScriptExecutionSpec spec);
}
