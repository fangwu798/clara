package com.clara.transport.concurrent;


import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * 守护线程工厂。
 */
public final class DaemonThreads {

    private DaemonThreads() {
    }

    /**
     * 创建带固定前缀的守护线程工厂，线程名格式为 {@code prefix + 递增序号}。
     *
     * @param prefix 线程名前缀，非 null
     * @return 线程工厂，非 null
     */
    public static ThreadFactory daemonThreadFactory(final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Thread newThread(@NotNull Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
