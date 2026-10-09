package com.clara.mcp.client;

import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;
import com.clara.mcp.client.call.McpPromptRequest;
import com.clara.mcp.client.call.McpPromptResult;
import com.clara.mcp.client.call.McpResourceContent;
import com.clara.mcp.client.call.McpResourceRequest;
import com.clara.mcp.client.call.McpToolCall;
import com.clara.mcp.client.call.McpToolResult;
import java.io.Closeable;
import java.util.List;

/**
 * MCP 客户端协议：面向单个已注册服务端的 JSON-RPC 交互入口。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 客户端的协议接口，由 {@code McpClientFactory} 按注册项创建，
 * 屏蔽传输细节（stdio / streamable http）。发现流程用列表方法分页拉取能力，网关用调用方法发起工具调用、
 * 资源读取与提示词获取。
 *
 * <p>谁是调用方：{@code DefaultMcpDiscoveryService}（初始化 + 分页列表）与 {@code DefaultMcpGateway}
 * （工具调用 / 资源读取 / 提示词获取）。两者都在使用后关闭客户端。
 *
 * <p>协议映射（实现约定）：方法名对应 MCP 的 JSON-RPC method——初始化 {@code initialize}、工具列表
 * {@code tools/list}、工具调用 {@code tools/call}、资源列表 {@code resources/list}、资源读取
 * {@code resources/read}、资源模板列表 {@code resources/templates/list}、提示词列表 {@code prompts/list}、
 * 提示词获取 {@code prompts/get}；请求与响应经 {@code com.clara.mcp.transport.McpJsonCodec} 编解码后由
 * {@code com.clara.mcp.transport.McpTransport} 收发。
 *
 * <p>生命周期与线程安全：一个实例绑定一条传输连接，不应跨线程并发复用；使用完毕必须 {@link #close()}
 * 释放传输资源，{@code Closeable} 的 {@code close()} 会抛出 {@link java.io.IOException}。
 */
public interface McpClient extends Closeable {
    /**
     * 与服务端完成协议握手，协商协议版本并取回服务端身份与能力。
     *
     * @param supportedProtocolVersions 客户端支持的协议版本列表，不可为 null 或空
     * @return 初始化结果（协议版本、身份、能力），非 null
     */
    McpInitialization initialize(List<String> supportedProtocolVersions);

    /**
     * 分页拉取工具列表。
     *
     * @param cursor 分页游标，首页传 null
     * @return 当前页工具与下一页游标，非 null
     */
    McpPage<McpToolDescriptor> listTools(String cursor);

    /**
     * 分页拉取资源列表。
     *
     * @param cursor 分页游标，首页传 null
     * @return 当前页资源与下一页游标，非 null
     */
    McpPage<McpResourceDescriptor> listResources(String cursor);

    /**
     * 分页拉取资源模板列表。
     *
     * @param cursor 分页游标，首页传 null
     * @return 当前页资源模板与下一页游标，非 null
     */
    McpPage<McpResourceTemplateDescriptor> listResourceTemplates(String cursor);

    /**
     * 分页拉取提示词列表。
     *
     * @param cursor 分页游标，首页传 null
     * @return 当前页提示词与下一页游标，非 null
     */
    McpPage<McpPromptDescriptor> listPrompts(String cursor);

    /**
     * 调用工具。
     *
     * @param call 工具调用入参（含工具名与参数），不可为 null
     * @return 工具调用结果，非 null
     */
    McpToolResult callTool(McpToolCall call);

    /**
     * 读取资源。
     *
     * @param request 资源读取入参（含资源 URI），不可为 null
     * @return 资源内容，非 null
     */
    McpResourceContent readResource(McpResourceRequest request);

    /**
     * 获取提示词。
     *
     * @param request 提示词获取入参（含提示词名与参数），不可为 null
     * @return 提示词结果，非 null
     */
    McpPromptResult getPrompt(McpPromptRequest request);
}
