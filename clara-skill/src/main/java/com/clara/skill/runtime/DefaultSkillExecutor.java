package com.clara.skill.runtime;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.skill.api.SkillDefinition;
import com.clara.skill.api.SkillEventObserver;
import com.clara.skill.api.SkillExecutionHandle;
import com.clara.skill.api.SkillExecutionRequest;
import com.clara.skill.api.SkillExecutionResult;
import com.clara.skill.api.SkillExecutionStatus;
import com.clara.skill.api.SkillExecutionType;
import com.clara.skill.api.SkillExecutor;
import com.clara.skill.api.SkillHealth;
import com.clara.skill.api.SkillLifecycle;
import com.clara.skill.api.SkillRegistry;
import com.clara.skill.api.SkillResumeRequest;

import java.io.Closeable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 负责技能解析、生命周期与健康检查、权限校验、Schema 校验以及同步、恢复和流式执行。
 *
 * <p>常规用法：按 {@link SkillExecutionType} 注册处理器后调用 {@link #execute}、{@link #resume}
 * 或 {@link #executeStream}；流式调用通过返回的 {@link SkillExecutionHandle} 查询状态或取消。
 * 执行器不再使用时应调用 {@link #close()}。
 *
 * <p>注意：只有已发布且健康的技能可以执行，请求上下文中的租户、权限、取消信号和截止时间都会被强制检查。
 * 同一种执行类型只能对应一个处理器；观察者回调运行在执行器线程中，不应执行长时间阻塞操作。
 */
public final class DefaultSkillExecutor implements SkillExecutor, Closeable {
    private final SkillRegistry registry;
    private final SkillSchemaValidator schemas;
    private final SkillPermissionAuthorizer permissions;
    private final SkillHealthResolver health;
    private final Map<SkillExecutionType, SkillHandler> handlers;
    private final ExecutorService calls, streams;

    public DefaultSkillExecutor(SkillRegistry registry, SkillSchemaValidator schemas,
                                SkillPermissionAuthorizer permissions, SkillHealthResolver health,
                                Map<SkillExecutionType, SkillHandler> handlers, int threads) {
        if (registry == null || schemas == null || permissions == null || health == null || handlers == null || threads <= 0) {
            throw new IllegalArgumentException("skill executor dependencies are invalid");
        }
        this.registry = registry;
        this.schemas = schemas;
        this.permissions = permissions;
        this.health = health;
        EnumMap<SkillExecutionType, SkillHandler> copiedHandlers =
                new EnumMap<SkillExecutionType, SkillHandler>(SkillExecutionType.class);
        copiedHandlers.putAll(handlers);
        this.handlers = Collections.unmodifiableMap(copiedHandlers);
        calls = Executors.newFixedThreadPool(threads, new SkillThreadFactory("call"));
        streams = Executors.newFixedThreadPool(threads, new SkillThreadFactory("stream"));
    }

    public SkillExecutionResult execute(final SkillExecutionRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        final SkillDefinition definition = authorize(request);
        if (!schemas.isValid(definition.getInputSchema(), request.getInputs())) schema();
        return invoke(definition, request.getContext().remainingMillis(System.currentTimeMillis()),
                new Invocation() {
                    public SkillExecutionResult call() throws Exception {
                        return handler(definition).execute(definition, request);
                    }
                });
    }

    public SkillExecutionResult resume(final SkillResumeRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        final SkillExecutionRequest execution = request.getExecutionRequest();
        final SkillDefinition definition = authorize(execution);
        if (!schemas.isValid(definition.getInputSchema(), execution.getInputs())) schema();
        return invoke(definition, execution.getContext().remainingMillis(System.currentTimeMillis()),
                new Invocation() {
                    public SkillExecutionResult call() throws Exception {
                        return handler(definition).resume(definition, request);
                    }
                });
    }

    public SkillExecutionHandle executeStream(final SkillExecutionRequest request, final SkillEventObserver observer) {
        if (request == null || observer == null) {
            throw new IllegalArgumentException("request and observer must not be null");
        }
        Future<?> future = streams.submit(new Runnable() {
            public void run() {
                notifyStarted(observer, request);
                try {
                    SkillExecutionResult result = execute(request);
                    notifyCompleted(observer, result);
                } catch (Throwable failure) {
                    notifyError(observer, failure);
                }
            }
        });
        return new FutureHandle(future);
    }

    public void close() {
        streams.shutdownNow();
        calls.shutdownNow();
    }

    private SkillDefinition authorize(SkillExecutionRequest request) {
        request.getContext().getCancellationToken().throwIfCancellationRequested(request.getContext().getTraceId());
        SkillDefinition definition = registry.get(request.getSkillId(), request.getVersion());
        if (definition.getLifecycle() != SkillLifecycle.PUBLISHED && definition.getLifecycle() != SkillLifecycle.DEPRECATED) {
            throw SkillErrors.error(ErrorCodes.SKILL_UNAVAILABLE, ErrorCategory.CAPABILITY);
        }
        SkillHealth currentHealth = health.health(definition);
        if (currentHealth == null || currentHealth == SkillHealth.BLOCKED) {
            throw SkillErrors.error(ErrorCodes.SKILL_UNAVAILABLE, ErrorCategory.CAPABILITY);
        }
        if (!permissions.isAllowed(request.getContext(), definition)) {
            throw SkillErrors.error(ErrorCodes.SKILL_PERMISSION_DENIED, ErrorCategory.AUTHORIZATION);
        }
        return definition;
    }

    private SkillHandler handler(SkillDefinition definition) {
        SkillHandler value = handlers.get(definition.getExecutionType());
        if (value == null) { throw SkillErrors.error(ErrorCodes.SKILL_HANDLER_NOT_FOUND, ErrorCategory.CONFIGURATION); }
        return value;
    }

    private SkillExecutionResult invoke(SkillDefinition definition, long remaining, Invocation invocation) {
        long timeout = Math.min(definition.getTimeoutMillis(), remaining);
        if (timeout <= 0L) { throw SkillErrors.error(ErrorCodes.SKILL_TIMEOUT, ErrorCategory.TIMEOUT); }
        Future<SkillExecutionResult> future = calls.submit(() -> invocation.call());
        try {
            SkillExecutionResult result = future.get(timeout, TimeUnit.MILLISECONDS);
            if (result == null || !definition.getSkillId().equals(result.getSkillId())
                    || !definition.getVersion().equals(result.getVersion())
                    || result.getStatus() == SkillExecutionStatus.SUCCEEDED
                    && !schemas.isValid(definition.getOutputSchema(), result.getOutputs())) schema();
            return result;
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw SkillErrors.error(ErrorCodes.SKILL_TIMEOUT, ErrorCategory.TIMEOUT);
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw SkillErrors.error(ErrorCodes.INVOCATION_CANCELLED, ErrorCategory.CANCELLED);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof ClaraException) { throw (ClaraException) cause; }
            if (cause instanceof RuntimeException) { throw (RuntimeException) cause; }
            throw SkillErrors.error(ErrorCodes.SKILL_UNAVAILABLE, ErrorCategory.INTERNAL);
        }
    }

    private static void schema() {
        throw SkillErrors.error(ErrorCodes.SKILL_SCHEMA_INVALID, ErrorCategory.VALIDATION);
    }

    private static void notifyStarted(SkillEventObserver o, SkillExecutionRequest r) {
        try {
            o.onStarted(r.getSkillId(), r.getVersion());
        } catch (RuntimeException ignored) {
        }
    }

    private static void notifyCompleted(SkillEventObserver o, SkillExecutionResult r) {
        try {
            o.onCompleted(r);
        } catch (RuntimeException ignored) {
        }
    }

    private static void notifyError(SkillEventObserver o, Throwable f) {
        try {
            o.onError(f);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * 定义。
     */
    private interface Invocation {
        SkillExecutionResult call() throws Exception;
    }

    /**
     * 控制并观察正在执行的异步结果。
     */
    private static final class FutureHandle implements SkillExecutionHandle {
        private final Future<?> future;

        FutureHandle(Future<?> value) {
            future = value;
        }

        public boolean cancel() {
            return future.cancel(true);
        }

        public boolean isDone() {
            return future.isDone();
        }
    }

    /**
     * 负责创建技能线程实例。
     */
    private static final class SkillThreadFactory implements ThreadFactory {
        private final String role;
        private final AtomicInteger sequence = new AtomicInteger();

        SkillThreadFactory(String role) {
            this.role = role;
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "clara-skill-" + role + "-" + sequence.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
