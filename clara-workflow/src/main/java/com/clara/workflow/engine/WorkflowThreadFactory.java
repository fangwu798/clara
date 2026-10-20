package com.clara.workflow.engine;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.NotNull;

/**
 * 工作流并行执行线程工厂：统一命名并设置为守护线程。
 */
final class WorkflowThreadFactory implements ThreadFactory {

    /** 线程序号。 */
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public Thread newThread(@NotNull Runnable runnable) {
        Thread thread = new Thread(runnable, "clara-workflow-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
