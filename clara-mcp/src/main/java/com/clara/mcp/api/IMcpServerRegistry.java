package com.clara.mcp.api;

import com.clara.mcp.registry.McpRegistrationStatus;
import com.clara.mcp.registry.McpServerRegistration;
import java.util.List;

/**
 * MCP 服务端注册表：登记并查找服务端接入信息。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层的服务端注册表接口。发现流程用它取回待发现的服务端，
 * 调用网关用它定位服务端并校验注册状态。
 *
 * <p>实现约定：{@link #register(McpServerRegistration)} 登记服务端；{@link #updateStatus} 更新注册状态；
 * {@link #listEnabled()} 列出可用的服务端；{@link #get(String)} 按服务端标识取回，
 * 未登记时返回 {@code null}（由调用方决定如何报错）。
 *
 * <p>线程安全与备注：实现必须支持并发读写（注册、发现、调用可能并行）；{@link #listEnabled(String)}
 * 只返回 {@code ACTIVE} 状态的服务端并按 {@code serverKey} 排序，返回只读快照。注册表只保存「怎么连」
 * 与「当前状态」，不保存能力快照。服务端标识在注册表内唯一。
 */
public interface IMcpServerRegistry {
    /**
     * 登记服务端。
     *
     * @param registration 服务端注册项，不可为 null；服务端标识重复登记时抛领域错误
     */
    void register(McpServerRegistration registration);

    /**
     * 更新指定服务端的注册状态。
     *
     * @param serverKey 服务端标识，不可为空白
     * @param status    目标注册状态，不可为 null
     */
    void updateStatus(String serverKey, McpRegistrationStatus status);

    /**
     * 列出可用的（ACTIVE）服务端，按服务端标识排序。
     *
     * @return 只读列表，非 null
     */
    List<McpServerRegistration> listEnabled();

    /**
     * 按服务端标识取回服务端。
     *
     * @param serverKey 服务端标识，不可为空白
     * @return 已登记的服务端；未登记时返回 {@code null}
     */
    McpServerRegistration get(String serverKey);
}
