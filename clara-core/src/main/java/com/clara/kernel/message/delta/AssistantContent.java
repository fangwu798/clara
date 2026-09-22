package com.clara.kernel.message.delta;

import com.clara.enums.RoleEnum;
import com.clara.kernel.api.message.IContent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 助手消息：线格式为 {@code {"role":"assistant","content":"..."}}。
 *
 * <p>是什么、在哪一层：契约层的消息实现，作为 {@code ModelParameters.messages} 的元素随请求体下发，
 * 用于把模型上一轮的回答放回对话历史。带工具调用的助手消息请用
 * {@link com.clara.tool.ToolCallContent}——那个类的线格式多了 {@code tool_calls}。
 *
 * <p>使用方法：{@code new AssistantContent("上一轮的答案")}；本类不可变，构造后即可安全共享。
 *
 * <p>线格式注意：{@link #getRoleType()} 已被 {@link JsonIgnore} 排除在 JSON 之外，下发键为
 * {@code role} 与 {@code content}。
 */
@Data
public final class AssistantContent implements IContent {
    /**
     * 正文文本，序列化为 {@code content}。
     */
    private final String content;

    /**
     * 创建助手消息。
     *
     * @param content 正文文本，可为 null
     */
    public AssistantContent(String content) {
        this.content = content;
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
        return RoleEnum.ASSISTANT;
    }
}
