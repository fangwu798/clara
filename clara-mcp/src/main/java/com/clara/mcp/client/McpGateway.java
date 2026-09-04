package com.clara.mcp.client;

public interface McpGateway {
    McpToolResult callTool(McpToolCall call);
    McpResourceContent readResource(McpResourceRequest request);
    McpPromptResult getPrompt(McpPromptRequest request);
}
