package com.clara.mcp.tool;

import com.clara.mcp.registry.McpServerSnapshot;
import com.clara.mcp.registry.capability.McpToolDescriptor;

/**
 * 把一次 MCP 能力快照中的工具登记为外部工具路由绑定。
 *
 * <p>是什么、在哪一层：clara-mcp 的登记器（包 {@code com.clara.mcp.tool}），衔接「MCP 发现出的能力快照」与
 * 「{@link ExternalToolCatalog} 的路由登记」。发现流程拿到快照后由它把每个工具名映射到承载
 * {@code tenantId} / {@code serverKey} / {@code snapshotId} / {@code schemaHash} 的 {@link ExternalToolBinding}。
 *
 * <p>谁创建谁调用：由 {@code ApplicationBootstrap} 持有，在其 {@code loadMcpServers} 流程发现成功后调用；
 * 也可由宿主直接对任意快照调用。本类不持有 MCP 客户端，也不发起调用，仅做纯登记。
 *
 * <p>生命周期与线程安全：无状态，可跨线程共享。
 */
public final class McpExternalToolRegistrar {

    /**
     * 登记快照中的全部工具。
     *
     * <p>每个工具登记为 {@code ExternalToolBinding(tenantId, snapshot.getServerKey(), snapshot.getSnapshotId(),
     * tool.getSchemaHash())}；同名工具按 {@link ExternalToolCatalog#register} 的约定以最后一次为准。
     *
     * @param tenantId 租户标识，不可为空白
     * @param snapshot 能力快照，不可为 null
     * @param catalog  目标外部工具路由目录，不可为 null
     * @return 登记的工具个数，非负
     * @throws IllegalArgumentException 入参非法时
     */
    public int register(String tenantId, McpServerSnapshot snapshot, ExternalToolCatalog catalog) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (catalog == null) {
            throw new IllegalArgumentException("catalog must not be null");
        }
        int registered = 0;
        for (McpToolDescriptor tool : snapshot.getTools()) {
            catalog.register(tool.getName(), new ExternalToolBinding(tenantId, snapshot.getServerKey(),
                    snapshot.getSnapshotId(), tool.getSchemaHash()));
            registered++;
        }
        return registered;
    }
}
