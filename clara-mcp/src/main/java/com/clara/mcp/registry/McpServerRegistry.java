package com.clara.mcp.registry;

import java.util.List;

/**
 * MCP 服务端注册表：登记并查找单个租户下的服务端接入信息。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层的服务端注册表接口。发现流程用它取回待发现的服务端，
 * 调用网关用它定位服务端并校验注册状态。
 *
 * <p>分区约定：以「租户 + 服务端标识」为键，同一服务端标识在不同租户下是不同的服务端。
 *
 * <p>实现约定：{@link #register(McpServerRegistration)} 登记服务端；{@link #updateStatus} 更新注册状态；
 * {@link #listEnabled(String)} 列出某租户下可用的服务端；{@link #get(String, String)} 按租户与服务端标识取回，
 * 未登记时返回 {@code null}（由调用方决定如何报错）。
 */
public interface McpServerRegistry {
    /**
     * 登记服务端。
     *
     * @param registration 服务端注册项，不可为 null；同一租户 + 服务端标识重复登记时抛领域错误
     */
    void register(McpServerRegistration registration);

    /**
     * 更新指定服务端的注册状态。
     *
     * @param tenantId  租户标识，不可为空白
     * @param serverKey 服务端标识，不可为空白
     * @param status    目标注册状态，不可为 null
     */
    void updateStatus(String tenantId, String serverKey, McpRegistrationStatus status);

    /**
     * 列出某租户下可用的（ACTIVE）服务端，按服务端标识排序。
     *
     * @param tenantId 租户标识，不可为空白
     * @return 只读列表，非 null
     */
    List<McpServerRegistration> listEnabled(String tenantId);

    /**
     * 按租户与服务端标识取回服务端。
     *
     * @param tenantId  租户标识，不可为空白
     * @param serverKey 服务端标识，不可为空白
     * @return 已登记的服务端；未登记时返回 {@code null}
     */
    McpServerRegistration get(String tenantId, String serverKey);
}
