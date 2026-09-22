package com.clara.kernel.message.delta;

import com.clara.enums.RoleEnum;
import com.clara.kernel.api.message.IContent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 系统消息：线格式为 {@code {"role":"system","content":"..."}}。
 *
 * <p>是什么、在哪一层：契约层的消息实现，作为 {@code ModelParameters.messages} 的元素随请求体下发，
 * 通常放在列表首位，用来设定模型的人设、输出格式与约束。
 *
 * <p>使用方法：{@code new SystemContent("你是一名严谨的技术编辑")}；本类不可变，构造后即可安全共享。
 *
 * <p>线格式注意：{@link #getRoleType()} 已被 {@link JsonIgnore} 排除在 JSON 之外，下发键为
 * {@code role} 与 {@code content}。
 */
@Data
public final class SystemContent implements IContent {
    /**
     * 正文文本，序列化为 {@code content}。
     */
    private final String content;

    /**
     * 创建系统消息。
     *
     * @param content 正文文本，可为 null
     */
    public SystemContent(String content) {
        this.content = content;
    }

    /**
     * 线格式的角色名。
     *
     * @return 固定为 {@code system}
     */
    @JsonProperty("role")
    public String getRole() {
        return "system";
    }

    @Override
    @JsonIgnore
    public RoleEnum getRoleType() {
        return RoleEnum.SYSTEM;
    }
}
