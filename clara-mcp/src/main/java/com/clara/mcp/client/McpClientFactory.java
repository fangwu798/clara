package com.clara.mcp.client;

import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.client.McpClient;

/**
 * 按服务端注册项创建 MCP 客户端。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层的客户端工厂接口。发现服务与调用网关通过它获得客户端，
 * 从而不感知注册项里的传输方式（stdio / streamable http）与连接细节。
 *
 * <p>实现约定：按 {@link McpServerRegistration#getTransportProfile()} 选择对应传输并创建
 * {@link McpClient}；每次调用返回一个新的、独立的客户端实例，由调用方负责关闭。
 */
public interface McpClientFactory {
    /**
     * 为给定服务端注册项创建客户端。
     *
     * @param registration 服务端注册项，不可为 null
     * @return 新建的客户端，非 null；由调用方负责关闭
     */
    McpClient create(McpServerRegistration registration);
}
