package com.clara.skill.executor;

import com.clara.context.CancellationToken;
import com.clara.enums.skill.SkillExecutionStatusEnum;
import com.clara.skill.SkillErrors;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 技能执行句柄：持可查询完成状态与取消信号；终态后重复取消无副作用。
 *
 * <p>实例由 {@link SkillExecutor#submit(String, String)} 创建，调用方通过它等待终态、读取结果或请求取消。
 */
public final class SkillExecutionHandle {

    /** 取消信号，执行线程据此在检查点提前退出。 */
    private final CancellationToken token = new CancellationToken();

    /** 完成闩锁：执行进入任一终态时放行等待方。 */
    private final CountDownLatch done = new CountDownLatch(1);

    /** 当前执行状态。 */
    private final AtomicReference<SkillExecutionStatusEnum> status = new AtomicReference<>(SkillExecutionStatusEnum.RUNNING);

    /** 成功时的输出文本，完成前为 null。 */
    private volatile String result;

    /** 失败原因，仅 FAILED 终态时非 null。 */
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    /**
     * 查询当前执行状态。
     */
    public SkillExecutionStatusEnum status() {
        return status.get();
    }

    /**
     * 请求取消执行，幂等。
     */
    public void cancel() {
        token.cancel();
    }

    /**
     * 阻塞等待执行进入终态。
     */
    public void await() {
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 限时等待执行进入终态。
     *
     * @return 在时限内进入终态返回 true，超时或线程被中断返回 false
     */
    public boolean await(long timeout, TimeUnit unit) {
        try {
            return done.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 读取执行结果；未完成时返回 null。
     */
    public String result() {
        return result;
    }

    /**
     * 读取执行结果并按终态抛出对应异常。
     *
     * @return 成功时的输出文本，非 null
     * @throws RuntimeException 取消 / 失败 / 未完成时
     */
    public String resultOrThrow() {
        SkillExecutionStatusEnum current = status.get();
        if (current == SkillExecutionStatusEnum.COMPLETED) {
            return result;
        }
        if (current == SkillExecutionStatusEnum.CANCELLED) {
            throw SkillErrors.cancelled();
        }
        if (current == SkillExecutionStatusEnum.FAILED) {
            RuntimeException cause = failure.get();
            if (cause != null) {
                throw cause;
            }
            throw new IllegalStateException("execution failed without a recorded cause");
        }
        throw new IllegalStateException("execution is still running");
    }

    /**
     * 是否已收到取消请求。
     */
    boolean isCancellationRequested() {
        return token.isCancellationRequested();
    }

    /**
     * 标记执行成功并放行等待方。
     *
     * @param output 成功输出
     */
    void complete(String output) {
        result = output;
        status.set(SkillExecutionStatusEnum.COMPLETED);
        done.countDown();
    }

    /**
     * 标记执行已取消并放行等待方。
     */
    void cancelDone() {
        status.set(SkillExecutionStatusEnum.CANCELLED);
        done.countDown();
    }

    /**
     * 记录失败原因、标记执行失败并放行等待方。
     *
     * @param error 失败原因
     */
    void fail(RuntimeException error) {
        failure.set(error);
        status.set(SkillExecutionStatusEnum.FAILED);
        done.countDown();
    }
}