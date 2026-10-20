package com.clara.skill.executor;

import com.clara.core.tool.IToolInvocation;

/**
 * 技能执行时的工具调用参数：只携带工具名与参数 JSON。
 *
 * <p>技能级执行没有模型侧的调用 ID，故 {@link #callId()} 返回 null。
 */
final class SkillInvocation implements IToolInvocation {

    /** 工具名。 */
    private final String name;

    /** 参数 JSON。 */
    private final String arguments;

    /**
     * 创建技能工具调用参数。
     *
     * @param name      工具名
     * @param arguments 参数 JSON
     */
    SkillInvocation(String name, String arguments) {
        this.name = name;
        this.arguments = arguments;
    }

    @Override
    public String callId() {
        return null;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String arguments() {
        return arguments;
    }
}