package com.clara.mcp.api;

import com.clara.mcp.client.call.McpPromptRequest;
import com.clara.mcp.client.call.McpPromptResult;
import com.clara.mcp.client.call.McpResourceContent;
import com.clara.mcp.client.call.McpResourceRequest;
import com.clara.mcp.client.call.McpToolCall;
import com.clara.mcp.client.call.McpToolResult;

/**
 * 面向编排层的 MCP 统一调用入口。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 对外暴露的调用门面，屏蔽客户端与协议细节，向编排层的工具往返
 * 提供稳定入口；工具调用、资源读取与提示词获取都经它完成。
 *
 * <p>谁创建谁调用：由宿主应用按依赖装配（注册表 + 能力目录 + 客户端工厂），编排层只持有本接口。
 * 入参携带服务端标识与能力快照标识，网关据此定位服务端并校验能力。
 *
 * <p>失败语义：服务端不存在、未处于可调用状态、能力未找到或 Schema 哈希不一致等情况，抛出携带
 * {@code MCP_*} 错误码的领域异常。
 *
 * <p>调用语义与备注：每次调用都新建临时客户端，按快照协议版本单版本协商并与快照身份比对，执行完在
 * {@code finally} 中关闭；不做自动重试。入口先检查取消令牌，再依次校验注册状态、快照健康度、能力存在性
 * 与 Schema 哈希，任一层不通过都不会把请求发给服务端。工具名的路由优先级由宿主决定，本接口只负责
 * 「已选中的外部工具怎么调」。
 */
public interface IMcpGateway {
    /**
     * 调用外部工具。
     *
     * @param call 工具调用入参，不可为 null
     * @return 工具调用结果，非 null
     */
    McpToolResult callTool(McpToolCall call);

    /**
     * 读取外部资源。
     *
     * @param request 资源读取入参，不可为 null
     * @return 资源内容，非 null
     */
    McpResourceContent readResource(McpResourceRequest request);

    /**
     * 获取外部提示词。
     *
     * @param request 提示词获取入参，不可为 null
     * @return 提示词结果，非 null
     */
    McpPromptResult getPrompt(McpPromptRequest request);
}
