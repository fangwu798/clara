package com.clara.kernel.message.delta;

import com.clara.enums.RoleEnum;
import com.clara.kernel.api.message.IContent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户消息：线格式为 {@code {"role":"user","content":"..."}}。
 *
 * <p>是什么、在哪一层：契约层的消息实现，作为 {@code ModelParameters.messages} 的元素随请求体下发。
 * 本类只承载用户侧的正文文本；角色由 {@link #getRole()} 固定给出，因此一个实例即一条完整的消息。
 *
 * <p>使用方法：{@code new UserContent("用一句话介绍你自己")} 放进消息列表即可；
 * 本类不可变，构造后即可安全共享。
 *
 * <p>线格式注意：{@link #getRoleType()} 是内部判别用的枚举，已用 {@link JsonIgnore} 排除在 JSON 之外；
 * 下发到平台的键是 {@code role} 与 {@code content}，不要依据默认的 getter 名推断线格式。
 */
@Data
public final class UserContent implements IContent {
    /**
     * 正文文本，序列化为 {@code content}。
     */
    private final String content;

    /**
     * 创建用户消息。
     *
     * @param content 正文文本，可为 null（发空消息通常没有意义，由调用方保证合理性）
     */
    public UserContent(String content) {
        this.content = content;
    }

    /**
     * 线格式的角色名。
     *
     * @return 固定为 {@code user}
     */
    @JsonProperty("role")
    public String getRole() {
        return "user";
    }

    @Override
    @JsonIgnore
    public RoleEnum getRoleType() {
        return RoleEnum.USER;
    }

    @Override
    public Map<String, Object> toCompletionMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", content);
        return message;
    }

    @Override
    public List<Map<String, Object>> toResponseInputItems() {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "input_text");
        part.put("text", content);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "message");
        item.put("role", "user");
        item.put("content", Collections.singletonList(part));
        return Collections.singletonList(item);
    }
}
