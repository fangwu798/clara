package com.clara.skill.executor;

import com.clara.core.skill.IToolInvoker;
import com.clara.dto.skill.ToolMethod;
import com.clara.skill.SkillErrors;
import com.clara.skill.registry.SkillRegistry;
import com.clara.dto.tool.ToolResult;

import java.util.concurrent.TimeUnit;

/**
 * 技能级执行编排：统一承载同步 / 流式 / 恢复三种执行方式与生命周期（取消、权限、超时、结果校验）。
 *
 * <p>原子脚本执行委托 {@link IToolInvoker}（进而 {@code IToolExecutor}），本类不重复实现脚本运行，
 * 只负责技能级的校验、状态机与取消 / 超时编排。
 */
public final class SkillExecutor {

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
        Thread worker = new Thread(() -> execute(toolName, argumentsJson, handle));
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
            ToolResult toolResult = toolInvoker.invoke(new SkillInvocation(toolName, argumentsJson));
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
}
