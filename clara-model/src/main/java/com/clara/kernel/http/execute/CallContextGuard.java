package com.clara.kernel.http.execute;

import com.clara.context.CallContext;
import com.clara.core.transport.IExecutionGuard;

/**
 * 把模型层 {@link CallContext} 适配为通用传输执行守卫。
 */
final class CallContextGuard implements IExecutionGuard {

    /** 被适配的模型层调用上下文。 */
    private final CallContext call;

    /**
     * 创建执行守卫。
     *
     * @param call 模型层调用上下文，不能为 null
     */
    CallContextGuard(CallContext call) {
        if (call == null) {
            throw new IllegalArgumentException("call must not be null");
        }
        this.call = call;
    }

    @Override
    public boolean isCancelled() {
        return call.getCancellationToken().isCancellationRequested();
    }

    @Override
    public long remainingMillis() {
        return call.remainingMillis(System.currentTimeMillis());
    }
}
