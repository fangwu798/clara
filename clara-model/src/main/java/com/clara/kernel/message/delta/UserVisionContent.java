package com.clara.kernel.message.delta;

import com.clara.core.message.IContent;
import com.clara.enums.RoleEnum;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模态用户消息：线格式为「文本 + 图片」的用户消息，用于图片识别（图 → 文）。
 *
 * <p>是什么、在哪一层：契约层的消息实现（包 {@code com.clara.kernel.message.delta}），作为
 * {@code messages} 的元素随请求体下发；与 {@link UserContent}（纯文本）并列，由调用方在需要携带图片时选用。
 *
 * <p>线格式：Chat Completions 渲染为 {@code content} 数组（{@code {"type":"text"}} 与
 * {@code {"type":FIELD_IMAGE_URL,FIELD_IMAGE_URL:{"url":...}}}）；Responses 渲染为 input item 列表（{@code input_text} 与
 * {@code input_image}）。协议差异由本类自行渲染，编码器无需分支。
 *
 * <p>图片引用写法：公网可访问的图片 URL，或 {@code data:image/<格式>;base64,<编码>} 形式的 base64。
 *
 * <p>本类不可变、线程安全。
 */
@Data
public final class UserVisionContent implements IContent {

    private static final String FIELD_IMAGE_URL = "image_url";

    /**
     * 本轮用户文本，可为空白（仅图片）。
     */
    private final String text;

    /**
     * 图片引用列表（URL 或 data URI），非 null。
     */
    private final List<String> images;

    /**
     * 创建多模态用户消息。
     *
     * @param text   用户文本，可为 null / 空白
     * @param images 图片引用列表，可为 null（视为空）
     */
    public UserVisionContent(String text, List<String> images) {
        this.text = text;
        this.images = images == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(images));
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
        List<Map<String, Object>> parts = new ArrayList<>();
        addTextPart(parts);
        for (String image : images) {
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("url", image);
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", FIELD_IMAGE_URL);
            part.put(FIELD_IMAGE_URL, reference);
            parts.add(part);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", parts);
        return message;
    }

    @Override
    public List<Map<String, Object>> toResponseInputItems() {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (hasText()) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "input_text");
            part.put("text", text);
            parts.add(part);
        }
        for (String image : images) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "input_image");
            part.put(FIELD_IMAGE_URL, image);
            parts.add(part);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "message");
        item.put("role", "user");
        item.put("content", parts);
        return Collections.singletonList(item);
    }

    private void addTextPart(List<Map<String, Object>> parts) {
        if (!hasText()) {
            return;
        }
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        parts.add(part);
    }

    private boolean hasText() {
        return text != null && !text.trim().isEmpty();
    }
}
