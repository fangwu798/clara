package com.clara.mcp.api;

import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpServerSnapshot;

/**
 * MCP 发现服务：初始化服务端并分页发现其能力，发布带协议版本与身份的能力快照。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层的发现入口，串起「注册 → 发现 → 目录 → 调用」闭环的前半段。
 *
 * <p>实现约定：{@link #discover(McpServerRegistration)} 只做发现并返回快照；{@link #refresh(String, String)}
 * 在发现成功后发布快照并把注册状态更新为 ACTIVE，协议不兼容标记 INCOMPATIBLE、身份变化标记 REVIEW_REQUIRED。
 *
 * <p>生命周期与备注：每次 {@code discover} / {@code refresh} 都新建一个客户端，完成后在 {@code finally}
 * 中关闭，因此可以重复执行且不会复用长连接；分页受最大页数限制并检测重复游标。发现只读取能力，
 * 不执行工具。
 */
public interface IMcpDiscoveryService {
    /**
     * 初始化给定服务端并分页发现其能力，形成能力快照（不发布、不改注册状态）。
     *
     * @param registration 服务端注册项，不可为 null
     * @return 能力快照，非 null
     */
    McpServerSnapshot discover(McpServerRegistration registration);

    /**
     * 重新发现指定服务端，发布快照并更新注册状态。
     *
     * @param tenantId  租户标识，不可为空白
     * @param serverKey 服务端标识，不可为空白
     */
    void refresh(String tenantId, String serverKey);
}
