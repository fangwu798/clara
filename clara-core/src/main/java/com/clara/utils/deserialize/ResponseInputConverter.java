package com.clara.utils.deserialize;

import com.clara.kernel.api.message.IContent;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.tool.ToolCall;
import com.clara.tool.ToolCallContent;
import com.clara.tool.ToolCallFunction;
import com.clara.tool.ToolContext;
import com.clara.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Chat Completions 的消息与工具声明转换为 OpenAI Responses 协议的线格式结构。
 *
 * <p>为什么需要：Responses 协议的 {@code input} 与 {@code tools} 与 Chat Completions 差异较大——
 * 文本消息的 {@code content} 从字符串变成内容片段数组 {@code [{"type":"input_text","text":...}]}，
 * 工具声明从 {@code {"type":"function","function":{...}}} 摊平成
 * {@code {"type":"function","name":...,"description":...,"parameters":...}}，
 * 工具结果 / 工具调用消息则变成 {@code function_call_output} / {@code function_call} 输入项。这些结构差异
 * 无法用字段名映射表达，因此收敛到本转换器。
 *
 * <p>使用方法：编码器在协议为 {@code RESPONSE} 时，对 {@code messages} 字段调用 {@link #toInput(List)}、
 * 对 {@code tools} 字段调用 {@link #toTools(List)}。本类无状态、构造器私有，可并发调用。
 */
public final class ResponseInputConverter {

    private ResponseInputConverter() {
    }

    /**
     * 把消息列表转换为 Responses 协议的 {@code input} 数组。
     *
     * @param messages 消息列表，可为 null 或空
     * @return 转换后的 input 项列表，非 null；输入为 null 或空时返回空列表
     */
    public static List<Map<String, Object>> toInput(List<IContent> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (IContent message : messages) {
            append(items, message);
        }
        return items;
    }

    /**
     * 把工具声明列表转换为 Responses 协议的 {@code tools} 数组（函数声明摊平）。
     *
     * @param tools 工具声明列表，可为 null 或空
     * @return 转换后的 tools 项列表，非 null；输入为 null 或空时返回空列表
     */
    public static List<Map<String, Object>> toTools(List<ToolContext> tools) {
        if (tools == null || tools.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ToolContext tool : tools) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("type", tool.getType());
            ToolSpecification function = tool.getFunction();
            if (function != null) {
                item.put("name", function.getName());
                if (function.getDescription() != null) {
                    item.put("description", function.getDescription());
                }
                item.put("parameters", function.getParameters());
            }
            items.add(item);
        }
        return items;
    }

    /**
     * 把单条消息追加为一条或多条 input 项。
     *
     * <p>工具调用消息（{@link ToolCallContent}）会展开为「文本（若有）+ 多个 function_call」，
     * 工具结果消息（{@link ToolContent}）转为 {@code function_call_output}。
     */
    private static void append(List<Map<String, Object>> items, IContent message) {
        if (message instanceof UserContent) {
            items.add(textMessage("user", ((UserContent) message).getContent()));
        } else if (message instanceof AssistantContent) {
            items.add(textMessage("assistant", ((AssistantContent) message).getContent()));
        } else if (message instanceof SystemContent) {
            items.add(textMessage("system", ((SystemContent) message).getContent()));
        } else if (message instanceof ToolContent) {
            ToolContent tool = (ToolContent) message;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("type", "function_call_output");
            item.put("call_id", tool.getToolCallId());
            item.put("output", tool.getContent());
            items.add(item);
        } else if (message instanceof ToolCallContent) {
            ToolCallContent call = (ToolCallContent) message;
            String content = call.getContent();
            if (content != null && !content.isEmpty()) {
                items.add(textMessage("assistant", content));
            }
            for (ToolCall toolCall : call.getToolCalls()) {
                items.add(functionCallItem(toolCall));
            }
        }
    }

    /**
     * 构造一条文本消息 input 项。
     *
     * <p>助手消息的正文是模型历史输出，内容片段类型用 {@code output_text}；用户 / 系统消息的正文是
     * 输入，用 {@code input_text}。两者不可混用，否则平台可能拒绝或产生错误的历史语义。
     */
    private static Map<String, Object> textMessage(String role, String text) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("type", "message");
        item.put("role", role);
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        Map<String, Object> part = new LinkedHashMap<String, Object>();
        part.put("type", "assistant".equals(role) ? "output_text" : "input_text");
        part.put("text", text);
        content.add(part);
        item.put("content", content);
        return item;
    }

    /**
     * 构造一条 function_call 输入项。
     */
    private static Map<String, Object> functionCallItem(ToolCall toolCall) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("type", "function_call");
        item.put("call_id", toolCall.getId());
        ToolCallFunction function = toolCall.getFunction();
        item.put("name", function == null ? null : function.getName());
        item.put("arguments", function == null ? null : function.getArguments());
        return item;
    }
}
