package com.clara.core.skill;

import com.clara.core.tool.IToolInvocation;
import com.clara.dto.tool.ToolResult;

/**
 * 工具调用接口：框架调用技能声明的工具方法，是工具往返循环的统一入口。
 *
 * <p>core 定义的契约接口，由 clara-skill 实现；框架拿到模型工具调用后通过本接口执行并取回可回填结果。
 */
public interface IToolInvoker {

    /**
     * 调用工具方法。
     *
     * @param invocation 模型返回的工具调用，不可为 null
     * @return 工具执行结果（携带与调用一致的 callId），非 null
     */
    ToolResult invoke(IToolInvocation invocation);
}
