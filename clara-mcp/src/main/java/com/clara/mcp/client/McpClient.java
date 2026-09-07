package com.clara.mcp.client;

import com.clara.mcp.registry.McpPromptDescriptor;
import com.clara.mcp.registry.McpResourceDescriptor;
import com.clara.mcp.registry.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.McpToolDescriptor;
import java.io.Closeable;
import java.util.List;
import java.util.Map;

/**
 * 提供访问MCP的客户端。
 */
public interface McpClient extends Closeable {
    McpInitialization initialize(List<String> supportedProtocolVersions);
    McpPage<McpToolDescriptor> listTools(String cursor);
    McpPage<McpResourceDescriptor> listResources(String cursor);
    McpPage<McpResourceTemplateDescriptor> listResourceTemplates(String cursor);
    McpPage<McpPromptDescriptor> listPrompts(String cursor);
    McpToolResult callTool(String name, Map<String, Object> arguments);
    McpResourceContent readResource(String uri);
    McpPromptResult getPrompt(String name, Map<String, Object> arguments);
}
