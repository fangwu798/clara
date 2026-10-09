package com.clara.tool;

import com.clara.enums.RoleEnum;
import com.clara.core.message.IContent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 助手要求调用工具的消息：线格式为
 * {@code {"role":"assistant","content":null,"tool_calls":[...]}}。
 *
 * <p>是什么、在哪一层：契约层的消息实现（包 {@code com.clara.tool}），是工具调用往返的第一轮回执——
 * 模型返回的 {@link ToolCall} 列表要原样放回 {@code ModelParameters.messages}，平台才会认可接下来那条
 * {@code role=tool} 的结果消息。它和 {@code AssistantContent} 的区别只在于多了 {@code tool_calls}。
 *
 * <p>使用方法：
 * <pre>{@code
 * messages.add(new ToolCallContent(response.getToolCalls()));
 * messages.add(new ToolContent(call.getId(), "{\"weather\":\"晴\"}"));
 * ModelParameters next = HttpRequest.builder().messages(messages).build();
 * }</pre>
 *
 * <p>线格式注意：{@code tool_calls} 中的 {@code id} 与函数名必须与响应里的完全一致，不要重排或改写；
 * 只保留文本回答时请用 {@code AssistantContent}。
 */
@Data
public final class ToolCallContent implements IContent {

    /**
     * 助手同时给出的文本；纯工具调用轮通常为 null，序列化为 {@code content}。
     */
    @JsonProperty("content")
    private final String content;

    /**
     * 模型要求调用的工具列表，序列化为 {@code tool_calls}。
     */
    @JsonProperty("tool_calls")
    private final List<ToolCall> toolCalls;

    /**
     * 创建携带工具调用的助手消息（不带文本）。
     *
     * @param toolCalls 模型返回的工具调用列表，不可为 null 或空
     * @throws IllegalArgumentException toolCalls 为 null 或空时
     */
    public ToolCallContent(List<ToolCall> toolCalls) {
        this(null, toolCalls);
    }

    /**
     * 创建携带工具调用的助手消息。
     *
     * @param content   助手同时给出的文本，可为 null
     * @param toolCalls 模型返回的工具调用列表，不可为 null 或空
     * @throws IllegalArgumentException toolCalls 为 null 或空时
     */
    public ToolCallContent(String content, List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("toolCalls must not be empty");
        }
        this.content = content;
        this.toolCalls = toolCalls;
    }

    /**
     * 线格式的角色名。
     *
     * @return 固定为 {@code assistant}
     */
    @JsonProperty("role")
    public String getRole() {
        return "assistant";
    }

    @Override
    @JsonIgnore
    public RoleEnum getRoleType() {
        return RoleEnum.TOOL_CALL;
    }

    @Override
    public Map<String, Object> toCompletionMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolCall call : toolCalls) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", call.getId());
            c.put("type", call.getType());
            Map<String, Object> fn = new LinkedHashMap<>();
            ToolCallFunction function = call.getFunction();
            if (function != null) {
                fn.put("name", function.getName());
                fn.put("arguments", function.getArguments());
            }
            c.put("function", fn);
            calls.add(c);
        }
        message.put("tool_calls", calls);
        return message;
    }

    @Override
    public List<Map<String, Object>> toResponseInputItems() {
        List<Map<String, Object>> items = new ArrayList<>();
        if (content != null && !content.isEmpty()) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "output_text");
            part.put("text", content);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "message");
            item.put("role", "assistant");
            item.put("content", Collections.singletonList(part));
            items.add(item);
        }
        for (ToolCall call : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "function_call");
            item.put("call_id", call.getId());
            ToolCallFunction function = call.getFunction();
            item.put("name", function == null ? null : function.getName());
            item.put("arguments", function == null ? null : function.getArguments());
            items.add(item);
        }
        return items;
    }
}
