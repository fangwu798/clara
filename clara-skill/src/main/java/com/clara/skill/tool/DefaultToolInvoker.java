package com.clara.skill.tool;

import com.clara.core.skill.IToolExecutor;
import com.clara.core.skill.IToolInvoker;
import com.clara.tool.IToolInvocation;
import com.clara.tool.ToolResult;

/**
 * 工具调用统一入口：框架拿到模型工具调用后经本类执行并取回可回填结果。
 *
 * <p>屏蔽工具方法定位与执行细节，统一工具调用视图；实际执行委托 {@link IToolExecutor}，
 * 结果按与调用一致的 {@code callId} 回填为 {@link ToolResult}。
 */
public final class DefaultToolInvoker implements IToolInvoker {

    private final IToolExecutor executor;

    /**
     * 创建工具调用器。
     *
     * @param executor 工具执行器，不可为 null
     */
    public DefaultToolInvoker(IToolExecutor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        this.executor = executor;
    }

    @Override
    public ToolResult invoke(IToolInvocation invocation) {
        if (invocation == null) {
            throw new IllegalArgumentException("invocation must not be null");
        }
        String output = executor.execute(invocation.name(), invocation.arguments());
        return new ToolResult(invocation.callId(), output);
    }
}
