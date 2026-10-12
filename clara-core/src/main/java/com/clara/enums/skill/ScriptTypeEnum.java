package com.clara.enums.skill;

/**
 * 工具方法脚本的运行类型。
 *
 * <p>业务约定脚本运行类型有 Python、JavaScript、Shell 三种（由独立进程执行），另有 {@link #BUILTIN}
 * 表示进程内 Java 内置工具（不落脚本、直接复用平台内部能力）。
 */
public enum ScriptTypeEnum {
    /** Python 脚本。 */
    PYTHON,
    /** JavaScript 脚本。 */
    JAVASCRIPT,
    /** Shell 脚本。 */
    SHELL,
    /**
     * 进程内 Java 内置工具，非脚本，用于需要直接复用平台内部能力的工具（如触发工作流）。
     */
    BUILTIN
}
