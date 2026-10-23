package com.clara.tool;

import com.clara.core.skill.IToolCatalog;
import com.clara.dto.tool.ToolContext;
import com.clara.dto.tool.ToolSpecification;
import com.clara.mcp.api.IMcpCapabilityCatalog;
import com.clara.mcp.api.IMcpServerRegistry;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpServerSnapshot;
import com.clara.mcp.registry.capability.McpToolDescriptor;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部 MCP 工具目录：把「已发现并已登记」的 MCP 工具声明成可下发给模型的工具。
 *
 * <p>是什么、在哪一层：application 层对 core {@link IToolCatalog} 的实现（包 {@code com.clara.tool}），
 * 位于「MCP 发现」与「工具下发」之间。{@code clara-mcp} 的发现流程把能力快照发布进
 * {@link IMcpCapabilityCatalog}、并按工具名登记进 {@code ExternalToolCatalog} 供调用路由；本类只读取
 * 同一批快照，把工具名 / 描述 / 入参 Schema 转成请求体里的工具声明，使模型「看得见」这些外部工具。
 *
 * <p>为什么需要本类：调用侧的路由（{@code CompositeToolInvoker}）早已能按工具名把调用转给 MCP 网关，
 * 但下发侧原先只声明技能注册表里的本地工具——于是模型永远不知道该调用哪个外部工具。补上本目录后，
 * 「发现 → 声明 → 调用」三环闭合。
 *
 * <p>取值口径：只列注册状态为可用的服务端（{@link IMcpServerRegistry#listEnabled()}）的最新快照；
 * 每个快照的工具顺序按服务端标识排序保持稳定。入参 Schema 直接沿用 MCP 声明的 JSON Schema，
 * 不额外包装，避免与外部工具的真实契约不一致。
 *
 * <p>生命周期与线程安全：只读引用注册表与能力目录，两者都可被后续发现流程更新，故每次调用都重新读取；
 * 实例可跨线程共享。
 */
public final class McpToolCatalog implements IToolCatalog {

    private final IMcpServerRegistry serverRegistry;
    private final IMcpCapabilityCatalog capabilityCatalog;

    /**
     * 创建外部工具目录。
     *
     * @param serverRegistry    服务端注册表，不可为 null
     * @param capabilityCatalog 能力目录，不可为 null
     * @throws IllegalArgumentException 任一参数非法时
     */
    public McpToolCatalog(IMcpServerRegistry serverRegistry, IMcpCapabilityCatalog capabilityCatalog) {
        if (serverRegistry == null) {
            throw new IllegalArgumentException("serverRegistry must not be null");
        }
        if (capabilityCatalog == null) {
            throw new IllegalArgumentException("capabilityCatalog must not be null");
        }
        this.serverRegistry = serverRegistry;
        this.capabilityCatalog = capabilityCatalog;
    }

    @Override
    public List<ToolContext> availableTools() {
        List<ToolContext> tools = new ArrayList<>();
        for (McpServerRegistration registration : serverRegistry.listEnabled()) {
            McpServerSnapshot snapshot = capabilityCatalog.latest(registration.getServerKey());
            if (snapshot == null) {
                continue;
            }
            for (McpToolDescriptor tool : snapshot.getTools()) {
                tools.add(new ToolContext(new ToolSpecification(
                        tool.getName(), tool.getDescription(), tool.getInputSchema())));
            }
        }
        return tools;
    }
}
