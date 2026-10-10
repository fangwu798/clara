package com.clara.tool;

import com.clara.context.CallContext;
import com.clara.context.ConvContext;
import com.clara.core.skill.IToolInvoker;
import com.clara.core.tool.IToolInvocation;
import com.clara.dto.tool.ToolResult;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.client.gateway.McpGateway;
import com.clara.mcp.client.call.McpToolCall;
import com.clara.mcp.client.call.McpToolResult;
import com.clara.skill.registry.SkillRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * 组合工具调用器：在本地技能工具与外部 MCP 工具之间按工具名分派，是工具往返的统一执行入口。
 *
 * <p>是什么、在哪一层：application 装配层（包 {@code com.clara.tool}）对 core 契约
 * {@link IToolInvoker} 的实现，交给 orchestration 的 {@code ToolRoundTripCoordinator} 使用。
 *
 * <p>分派规则（本地优先、外部兜底）：
 * <ol>
 *   <li><b>本地技能</b>：若 {@link SkillRegistry#findToolByName(String)} 命中，直接委托注入的本地
 *       {@link IToolInvoker}（skill 模块的 {@code DefaultToolInvoker}）执行；</li>
 *   <li><b>外部工具</b>：否则查 {@link ExternalToolCatalog#find(String)}，命中则用当前
 *       {@link ConvContext#current()} 与 {@link ConvContext#currentCall()} 和绑定信息构造
 *       {@link McpToolCall}，经 {@link McpGateway#callTool(McpToolCall)} 调用，把返回内容序列化为 JSON
 *       文本作为 {@link ToolResult#getOutput()}（{@code callId} 沿用 {@link IToolInvocation#callId()}）；</li>
 *   <li>两处都未命中：抛领域错误，指明本地技能未注册且外部工具未登记。</li>
 * </ol>
 *
 * <p>降级语义：外部工具目录为空时，非本地工具一律按「未找到」处理；{@code mcpGateway} 为 null 时外部分支
 * 抛「未接入」错误。构造函数要求 {@code skillRegistry} / {@code localToolInvoker} / {@code externalToolCatalog}
 * 非 null。
 */
public final class CompositeToolInvoker implements IToolInvoker {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE =
            new TypeReference<Map<String, Object>>() { };

    private final SkillRegistry skillRegistry;
    private final IToolInvoker localToolInvoker;
    private final McpGateway mcpGateway;
    private final ExternalToolCatalog externalToolCatalog;

    /**
     * 创建组合工具调用器。
     *
     * @param skillRegistry      技能注册表，用于判定是否为本地工具，不可为 null
     * @param localToolInvoker   本地工具执行入口（skill 模块实现），不可为 null
     * @param mcpGateway         MCP 调用门面，可为 null（null 时外部分支抛「未接入」错误）
     * @param externalToolCatalog 外部工具路由目录，不可为 null
     * @throws IllegalArgumentException 除 mcpGateway 外的依赖为 null 时
     */
    public CompositeToolInvoker(SkillRegistry skillRegistry, IToolInvoker localToolInvoker,
            McpGateway mcpGateway, ExternalToolCatalog externalToolCatalog) {
        if (skillRegistry == null) {
            throw new IllegalArgumentException("skillRegistry must not be null");
        }
        if (localToolInvoker == null) {
            throw new IllegalArgumentException("localToolInvoker must not be null");
        }
        if (externalToolCatalog == null) {
            throw new IllegalArgumentException("externalToolCatalog must not be null");
        }
        this.skillRegistry = skillRegistry;
        this.localToolInvoker = localToolInvoker;
        this.mcpGateway = mcpGateway;
        this.externalToolCatalog = externalToolCatalog;
    }

    @Override
    public ToolResult invoke(IToolInvocation invocation) {
        if (invocation == null) {
            throw new IllegalArgumentException("invocation must not be null");
        }
        String toolName = invocation.name();
        if (toolName == null || toolName.trim().isEmpty()) {
            throw error(ErrorCodes.INVALID_ARGUMENT, ErrorCategory.VALIDATION, "工具名不能为空");
        }
        if (skillRegistry.findToolByName(toolName) != null) {
            return localToolInvoker.invoke(invocation);
        }
        ExternalToolBinding binding = externalToolCatalog.find(toolName);
        if (binding != null) {
            return invokeExternal(invocation, binding);
        }
        throw error(ErrorCodes.SKILL_NOT_FOUND, ErrorCategory.VALIDATION,
                "工具未找到：" + toolName + "（本地技能未注册，且外部工具未登记）");
    }

    /**
     * 经 MCP 网关执行外部工具调用。
     *
     * @param invocation 工具调用，非 null
     * @param binding    已命中的外部工具路由绑定，非 null
     * @return 工具结果（callId 与调用一致，output 为 MCP 内容的 JSON 文本），非 null
     */
    private ToolResult invokeExternal(IToolInvocation invocation, ExternalToolBinding binding) {
        if (mcpGateway == null) {
            throw error(ErrorCodes.CAPABILITY_NOT_SUPPORTED, ErrorCategory.CAPABILITY,
                    "外部工具未接入：" + invocation.name() + "（MCP 网关未装配）");
        }
        ConvContext conversation = ConvContext.current();
        if (conversation == null) {
            throw error(ErrorCodes.INVALID_ARGUMENT, ErrorCategory.VALIDATION,
                    "外部工具调用缺少会话上下文：" + invocation.name());
        }
        CallContext call = conversation.currentCall();
        if (call == null) {
            throw error(ErrorCodes.INVALID_ARGUMENT, ErrorCategory.VALIDATION,
                    "外部工具调用缺少调用上下文：" + invocation.name());
        }
        McpToolCall mcpCall = new McpToolCall(binding.getTenantId(), binding.getServerKey(),
                binding.getSnapshotId(), invocation.name(), binding.getSchemaHash(),
                arguments(invocation), conversation, call);
        McpToolResult result = mcpGateway.callTool(mcpCall);
        return new ToolResult(invocation.callId(), output(result));
    }

    /**
     * 把模型生成的入参 JSON 字符串解析为参数 map。
     *
     * @param invocation 工具调用，非 null
     * @return 参数 map，非 null（入参为空时为空 map）
     */
    private static Map<String, Object> arguments(IToolInvocation invocation) {
        String json = invocation.arguments();
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return JSON.readValue(json, ARGUMENTS_TYPE);
        } catch (IOException e) {
            throw error(ErrorCodes.INVALID_ARGUMENT, ErrorCategory.VALIDATION,
                    "外部工具入参不是合法 JSON：" + invocation.name());
        }
    }

    /**
     * 把 MCP 工具结果内容序列化为 JSON 文本。
     *
     * @param result MCP 工具结果，非 null
     * @return JSON 文本，非 null
     */
    private static String output(McpToolResult result) {
        try {
            return JSON.writeValueAsString(result.getContent());
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize MCP tool result", e);
        }
    }

    private static ClaraException error(String code, ErrorCategory category, String message) {
        return new ClaraException(new ClaraError(code, category, message, false, null, null, null, null,
                Collections.<String, String>emptyMap()));
    }
}
