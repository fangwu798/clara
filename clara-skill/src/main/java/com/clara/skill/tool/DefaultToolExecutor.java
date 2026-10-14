package com.clara.skill.tool;

import com.clara.api.IScriptExecutor;
import com.clara.context.CancellationToken;
import com.clara.context.ConvContext;
import com.clara.core.skill.IToolExecutor;
import com.clara.dto.skill.ToolMethod;
import com.clara.skill.executor.SkillPermissionAuthorizer;
import com.clara.skill.executor.SkillSchemaValidator;
import com.clara.skill.registry.SkillRegistry;

/**
 * 工具方法执行的统一调度与分发入口：按运行类型经注册表分派到具体脚本执行器。
 *
 * <p>执行链：定位工具方法 → 权限校验 → 入参 Schema 校验 → 分派执行 → 输出校验。
 * 脚本运行环境差异由 {@link ScriptExecutorRegistry} 屏蔽，新增脚本类型无需改动本类。
 */
public final class DefaultToolExecutor implements IToolExecutor {

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    private final SkillRegistry registry;
    private final ScriptExecutorRegistry executors;
    private final SkillSchemaValidator schemaValidator;
    private final SkillPermissionAuthorizer permissionAuthorizer;
    private final long defaultTimeoutMillis;

    /**
     * 使用默认执行器注册表、默认校验器与放行全部权限构造。
     *
     * @param registry 技能注册表，不可为 null
     */
    public DefaultToolExecutor(SkillRegistry registry) {
        this(registry, new ScriptExecutorRegistry(), new SkillSchemaValidator(),
                new SkillPermissionAuthorizer(), DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * 显式装配依赖构造。
     *
     * @param registry            技能注册表，不可为 null
     * @param executors           执行器注册表，不可为 null
     * @param schemaValidator     输入 / 输出 Schema 校验器，不可为 null
     * @param permissionAuthorizer 权限校验器，不可为 null
     * @param defaultTimeoutMillis 单次脚本执行的默认硬超时（毫秒），必须为正
     */
    public DefaultToolExecutor(SkillRegistry registry, ScriptExecutorRegistry executors,
            SkillSchemaValidator schemaValidator, SkillPermissionAuthorizer permissionAuthorizer,
            long defaultTimeoutMillis) {
        if (registry == null || executors == null || schemaValidator == null || permissionAuthorizer == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (defaultTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("defaultTimeoutMillis must be positive");
        }
        this.registry = registry;
        this.executors = executors;
        this.schemaValidator = schemaValidator;
        this.permissionAuthorizer = permissionAuthorizer;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    @Override
    public String execute(String toolName, String argumentsJson) {
        ToolMethod method = registry.requireToolByName(toolName);
        permissionAuthorizer.checkAllowed(method);
        schemaValidator.validateInput(method, argumentsJson);

        IScriptExecutor executor = executors.resolve(method.getType());
        ScriptExecutionSpec spec = new ScriptExecutionSpec(method.getType(), method.getScript(),
                argumentsJson, defaultTimeoutMillis, currentCancellationToken());
        String output = executor.execute(spec);

        schemaValidator.validateOutput(method, output);
        return output;
    }

    /**
     * 取当前线程所在会话的取消令牌，随执行描述下发给脚本执行器。
     *
     * <p>这样外部（如控制台 ESC、Web 层的客户端断开）置位会话令牌即可让正在运行的脚本在轮询检查点立刻终止；
     * 线程未绑定会话时返回 null（脚本执行不响应外部取消）。
     */
    private static CancellationToken currentCancellationToken() {
        ConvContext context = ConvContext.current();
        return context == null ? null : context.getCancellationToken();
    }
}
