package com.clara.mcp.client.call;

import com.clara.mcp.registry.ImmutableValues;
import java.util.List;
import java.util.Map;

/**
 * 封装MCP提示词操作的执行结果：描述、消息内容与错误标记。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 的调用结果值对象，由 {@code McpGateway.getPrompt} 返回给编排层。
 *
 * <p>字段来源：对应 MCP {@code prompts/get} 返回的 {@code description} 与 {@code messages}；消息为
 * 结构化对象（角色 + 内容），统一以只读 map 列表承载，与工具结果、资源内容的表示保持一致。{@code error}
 * 标记服务端是否以错误语义返回。
 *
 * <p>生命周期与线程安全：构造后不可变，可跨线程共享。
 *
 * <p>失败语义：{@code messages} 为 null 或含 null 时构造抛 {@link IllegalArgumentException}。
 */
public final class McpPromptResult {
    private final String description;
    private final List<Map<String, Object>> messages;
    private final boolean error;

    public McpPromptResult(String description, List<Map<String, Object>> messages, boolean error) {
        if (messages == null || messages.contains(null)) {
            throw new IllegalArgumentException("messages must not be null or contain null");
        }
        this.description = description;
        this.messages = ImmutableValues.copyMapList(messages);
        this.error = error;
    }

    public String getDescription() { return description; }
    public List<Map<String, Object>> getMessages() { return messages; }
    public boolean isError() { return error; }
}
