package com.clara.kernel.message.delta;

import com.clara.enums.RoleEnum;
import com.clara.core.message.IContent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具结果消息：线格式为 {@code {"role":"tool","tool_call_id":"...","content":"..."}}。
 *
 * <p>是什么、在哪一层：契约层的消息实现，是工具调用往返的第二轮——模型返回
 * {@link com.clara.dto.tool.ToolCall} 后，调用方执行工具，再用本类把执行结果放回
 * {@code ModelParameters.messages}，模型据此继续作答。
 *
 * <p>使用方法：
 * <pre>{@code
 * messages.add(new ToolCallContent(toolCalls));            // 助手要求调用的工具
 * messages.add(new ToolContent(call.getId(), "{\"weather\":\"晴\"}"));  // 按同一 id 回填结果
 * }</pre>
 *
 * <p>线格式注意：{@code toolCallId} 的下发键是 {@code tool_call_id}（下划线），
 * 必须与助手消息里 {@code tool_calls[].id} 完全一致，否则平台报「找不到对应的工具调用」。
 */
@Data
public final class ToolContent implements IContent {
    /**
     * 工具返回的内容，通常是一段 JSON 文本，序列化为 {@code content}。
     */
    private final String content;

    /**
     * 对应的工具调用 ID，序列化为 {@code tool_call_id}。
     */
    @JsonProperty("tool_call_id")
    private final String toolCallId;

    /**
     * 创建工具结果消息。
     *
     * @param toolCallId 对应的工具调用 ID，应与助手消息中 {@code tool_calls[].id} 一致，可为 null
     * @param content    工具返回内容，可为 null
     */
    public ToolContent(String toolCallId, String content) {
        this.toolCallId = toolCallId;
        this.content = content;
    }

    /**
     * 线格式的角色名。
     *
     * @return 固定为 {@code tool}
     */
    @JsonProperty("role")
    public String getRole() {
        return "tool";
    }

    @Override
    @JsonIgnore
    public RoleEnum getRoleType() {
        return RoleEnum.TOOL;
    }

    @Override
    public Map<String, Object> toCompletionMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("content", content);
        return message;
    }

    @Override
    public List<Map<String, Object>> toResponseInputItems() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "function_call_output");
        item.put("call_id", toolCallId);
        item.put("output", content);
        return Collections.singletonList(item);
    }
}
