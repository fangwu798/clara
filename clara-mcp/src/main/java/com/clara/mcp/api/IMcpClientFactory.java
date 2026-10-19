package com.clara.mcp.api;

import com.clara.mcp.registry.McpServerRegistration;

/**
 * 按服务端注册项创建 MCP 客户端。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层的客户端工厂接口。发现服务与调用网关通过它获得客户端，
 * 从而不感知注册项里的传输方式（stdio / streamable http）与连接细节。
 *
 * <p>实现约定：按 {@link McpServerRegistration#getTransportProfile()} 选择对应传输并创建
 * {@link IMcpClient}；每次调用返回一个新的、独立的客户端实例，由调用方负责关闭。
 *
 * <p>备注：创建客户端只完成参数校验与传输装配，不建立连接；真正握手发生在
 * {@link IMcpClient#initialize(java.util.List)}。stdio 的可执行文件允许清单、HTTP 的协议 / 域名白名单
 * 都在创建阶段校验，未通过时抛出领域错误，而不是返回半可用客户端。
 */
public interface IMcpClientFactory {
    /**
     * 为给定服务端注册项创建客户端。
     *
     * @param registration 服务端注册项，不可为 null
     * @return 新建的客户端，非 null；由调用方负责关闭
     */
    IMcpClient create(McpServerRegistration registration);
}
