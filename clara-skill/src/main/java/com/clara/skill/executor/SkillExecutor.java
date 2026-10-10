package com.clara.skill.executor;

import com.clara.context.CancellationToken;
import com.clara.core.skill.IToolInvoker;
import com.clara.dto.skill.ToolMethod;
import com.clara.skill.SkillErrors;
import com.clara.skill.registry.SkillRegistry;
import com.clara.core.tool.IToolInvocation;
import com.clara.dto.tool.ToolResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 技能级执行编排：统一承载同步 / 流式 / 恢复三种执行方式与生命周期（取消、权限、超时、结果校验）。
 *
 * <p>原子脚本执行委托 {@link IToolInvoker}（进而 {@code IToolExecutor}），本类不重复实现脚本运行，
 * 只负责技能级的校验、状态机与取消 / 超时编排。
 */
public final class SkillExecutor {

    /**
     * 技能执行状态机：RUNNING → COMPLETED / CANCELLED / FAILED。
     */
    public enum SkillExecutionStatus {
        RUNNING, COMPLETED, CANCELLED, FAILED
    }

    /**
     * 技能执行句柄：持可查询完成状态与取消信号；终态后重复取消无副作用。
     */
    public static final class SkillExecutionHandle {

        private final CancellationToken token = new CancellationToken();
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<SkillExecutionStatus> status =
                new AtomicReference<SkillExecutionStatus>(SkillExecutionStatus.RUNNING);
        private volatile String result;
        private volatile RuntimeException failure;

        /**
         * 查询当前执行状态。
         */
        public SkillExecutionStatus status() {
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
            SkillExecutionStatus current = status.get();
            if (current == SkillExecutionStatus.COMPLETED) {
                return result;
            }
            if (current == SkillExecutionStatus.CANCELLED) {
                throw SkillErrors.cancelled();
            }
            if (current == SkillExecutionStatus.FAILED) {
                throw failure;
            }
            throw new IllegalStateException("execution is still running");
        }

        private boolean isCancellationRequested() {
            return token.isCancellationRequested();
        }

        private void complete(String output) {
            result = output;
            status.set(SkillExecutionStatus.COMPLETED);
            done.countDown();
        }

        private void cancelDone() {
            status.set(SkillExecutionStatus.CANCELLED);
            done.countDown();
        }

        private void fail(RuntimeException error) {
            failure = error;
            status.set(SkillExecutionStatus.FAILED);
            done.countDown();
        }
    }

    private final SkillRegistry registry;
    private final IToolInvoker toolInvoker;
    private final SkillSchemaValidator schemaValidator;
    private final SkillPermissionAuthorizer permissionAuthorizer;

    public SkillExecutor(SkillRegistry registry, IToolInvoker toolInvoker) {
        this(registry, toolInvoker, new SkillSchemaValidator(), new SkillPermissionAuthorizer());
    }

    public SkillExecutor(SkillRegistry registry, IToolInvoker toolInvoker,
            SkillSchemaValidator schemaValidator, SkillPermissionAuthorizer permissionAuthorizer) {
        if (registry == null || toolInvoker == null) {
            throw new IllegalArgumentException("registry and toolInvoker must not be null");
        }
        this.registry = registry;
        this.toolInvoker = toolInvoker;
        this.schemaValidator = schemaValidator == null ? new SkillSchemaValidator() : schemaValidator;
        this.permissionAuthorizer = permissionAuthorizer == null ? new SkillPermissionAuthorizer() : permissionAuthorizer;
    }

    /**
     * 同步执行：阻塞等待执行进入终态后返回结果。
     */
    public String executeSync(String toolName, String argumentsJson) {
        SkillExecutionHandle handle = submit(toolName, argumentsJson);
        handle.await();
        return handle.resultOrThrow();
    }

    /**
     * 带超时的同步执行：超过时限未完成则取消并抛出超时异常。
     */
    public String executeSync(String toolName, String argumentsJson, long timeoutMillis) {
        SkillExecutionHandle handle = submit(toolName, argumentsJson);
        if (!handle.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            handle.cancel();
            throw SkillErrors.timeout(toolName);
        }
        return handle.resultOrThrow();
    }

    /**
     * 流式（异步）执行：提交后立即返回句柄，由调用方通过句柄观察与取消。
     */
    public SkillExecutionHandle submit(String toolName, String argumentsJson) {
        final SkillExecutionHandle handle = new SkillExecutionHandle();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                execute(toolName, argumentsJson, handle);
            }
        });
        worker.setDaemon(true);
        worker.start();
        return handle;
    }

    /**
     * 恢复执行：原子脚本无可恢复中间态，恢复即重新发起一次执行。
     */
    public SkillExecutionHandle resume(String toolName, String argumentsJson) {
        return submit(toolName, argumentsJson);
    }

    private void execute(String toolName, String argumentsJson, SkillExecutionHandle handle) {
        try {
            ToolMethod method = registry.requireToolByName(toolName);
            permissionAuthorizer.checkAllowed(method);
            schemaValidator.validateInput(method, argumentsJson);
            if (handle.isCancellationRequested()) {
                handle.cancelDone();
                return;
            }
            ToolResult toolResult = toolInvoker.invoke(new Invocation(toolName, argumentsJson));
            String output = toolResult.getOutput();
            schemaValidator.validateOutput(method, output);
            if (handle.isCancellationRequested()) {
                handle.cancelDone();
                return;
            }
            handle.complete(output);
        } catch (RuntimeException error) {
            handle.fail(error);
        }
    }

    private static final class Invocation implements IToolInvocation {
        private final String name;
        private final String arguments;

        Invocation(String name, String arguments) {
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
}
