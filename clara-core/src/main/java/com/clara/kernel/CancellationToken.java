package com.clara.kernel;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 协调一次调用中的协作式取消。
 */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public boolean cancel() { return cancelled.compareAndSet(false, true); }
    public boolean isCancellationRequested() { return cancelled.get(); }
    public void throwIfCancellationRequested(String traceId) {
        if (cancelled.get()) { throw new ClaraException(cancelledError(traceId)); }
    }
    private ClaraError cancelledError(String traceId) {
        return ClaraError.cancelled(traceId);
    }
}
