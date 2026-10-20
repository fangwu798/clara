package com.clara.workflow.planner;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 规划器线程工厂：创建带角色前缀的守护线程，便于区分调度线程与任务调用线程。
 */
final class PlannerThreadFactory implements ThreadFactory {

    /** 线程序号，同一角色内递增。 */
    private final AtomicInteger sequence = new AtomicInteger();

    /** 线程角色前缀，如 schedule / call。 */
    private final String role;

    /**
     * 创建任务调用线程工厂（角色为 call）。
     */
    PlannerThreadFactory() {
        this("call");
    }

    /**
     * 创建指定角色的线程工厂。
     *
     * @param role 线程角色前缀
     */
    PlannerThreadFactory(String role) {
        this.role = role;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "clara-planner-" + role + "-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}