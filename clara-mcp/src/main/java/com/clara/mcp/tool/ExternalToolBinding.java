package com.clara.mcp.tool;

import lombok.Getter;

/**
 * 外部工具路由绑定：把模型返回的工具名映射到某个 MCP 服务端快照上的具体工具。
 *
 * <p>是什么、在哪一层：clara-mcp 的值对象（包 {@code com.clara.mcp.tool}），是「工具名 → 外部工具」的
 * 路由信息载体。一个绑定记录了服务端标识、发现时固化的快照标识与 Schema 哈希，
 * 足以在调用时构造 {@code McpToolCall}。
 *
 * <p>谁创建谁调用：由宿主 / 能力发现流程在发现到外部工具后创建并登记进
 * {@link ExternalToolCatalog}，工具往返时由宿主的工具调用分派器（CompositeToolInvoker）读取并据此发起 MCP 调用。
 *
 * <p>生命周期与线程安全：构造后不可变，可跨线程共享。
 *
 * <p>失败语义：任一字段为 null 或空白时构造抛 {@link IllegalArgumentException}。
 */
@Getter
public final class ExternalToolBinding {

    /**
     * -- GETTER --
     *
     * @return 服务端标识，非空白
     */
    private final String serverKey;
    /**
     * -- GETTER --
     *
     * @return 发现时固化的快照标识，非空白
     */
    private final String snapshotId;
    /**
     * -- GETTER --
     *
     * @return 工具的 Schema 哈希，非空白
     */
    private final String schemaHash;

    /**
     * 创建外部工具路由绑定。
     *
     * @param serverKey  服务端标识，不可为空白
     * @param snapshotId 发现时固化的快照标识，不可为空白
     * @param schemaHash 工具的 Schema 哈希，不可为空白
     * @throws IllegalArgumentException 任一字段为 null 或空白时
     */
    public ExternalToolBinding(String serverKey, String snapshotId, String schemaHash) {
        require(serverKey, "serverKey");
        require(snapshotId, "snapshotId");
        require(schemaHash, "schemaHash");
        this.serverKey = serverKey;
        this.snapshotId = snapshotId;
        this.schemaHash = schemaHash;
    }

    private static void require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
