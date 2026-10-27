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
 * 助手消息：线格式为 {@code {"role":ROLE_ASSISTANT,"content":"..."}}。
 *
 * <p>是什么、在哪一层：契约层的消息实现，作为 {@code ModelParameters.messages} 的元素随请求体下发，
 * 用于把模型上一轮的回答放回对话历史。带工具调用的助手消息请用
 * {@link com.clara.dto.tool.ToolCallContent}——那个类的线格式多了 {@code tool_calls}。
 *
 * <p>使用方法：{@code new AssistantContent("上一轮的答案")}；本类不可变，构造后即可安全共享。
 *
 * <p>线格式注意：{@link #getRoleType()} 已被 {@link JsonIgnore} 排除在 JSON 之外，下发键为
 * {@code role} 与 {@code content}。
 */
@Data
public final class AssistantContent implements IContent {

    private static final String ROLE_ASSISTANT = "assistant";
    /**
     * 正文文本，序列化为 {@code content}。
     */
    private final String content;

    /**
     * 本次模型调用的 token 用量；仅用于历史展示与持久化，不参与协议渲染。
     */
    @JsonIgnore
    private final TokenUsage usage;

    /**
     * 产生本回答的模型标识（形如 {@code PROVIDER/model}）；仅用于历史展示与持久化，不参与协议渲染。
     *
     * <p>与 {@link #usage} 一起记录，使 {@code /usage} 能按模型统计历史消耗；旧历史文件没有该字段时为 null。
     */
    @JsonIgnore
    private final String model;

    /**
     * 创建助手消息。
     *
     * @param content 正文文本，可为 null
     */
    public AssistantContent(String content) {
        this(content, null, null);
    }

    /**
     * 创建带 token 用量的助手消息。
     *
     * @param content 正文文本，可为 null
     * @param usage   本次模型调用用量，可为 null
     */
    public AssistantContent(String content, TokenUsage usage) {
        this(content, usage, null);
    }

    /**
     * 创建带 token 用量与模型标识的助手消息。
     *
     * @param content 正文文本，可为 null
     * @param usage   本次模型调用用量，可为 null
     * @param model   产生本回答的模型标识，可为 null（旧历史或来源未知）
     */
    public AssistantContent(String content, TokenUsage usage, String model) {
        this.content = content;
        this.usage = usage;
        this.model = model;
    }

    /**
     * 线格式的角色名。
     *
     * @return 固定为 {@code assistant}
     */
    @JsonProperty("role")
    public String getRole() {
        return ROLE_ASSISTANT;
    }

    @Override
    @JsonIgnore
    public RoleEnum getRoleType() {
        return RoleEnum.ASSISTANT;
    }

    @Override
    public Map<String, Object> toCompletionMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", ROLE_ASSISTANT);
        message.put("content", content);
        return message;
    }

    @Override
    public List<Map<String, Object>> toResponseInputItems() {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "output_text");
        part.put("text", content);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "message");
        item.put("role", ROLE_ASSISTANT);
        item.put("content", Collections.singletonList(part));
        return Collections.singletonList(item);
    }
}
