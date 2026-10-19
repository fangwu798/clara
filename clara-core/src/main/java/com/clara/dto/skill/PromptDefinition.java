package com.clara.dto.skill;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 提示词定义：提示词的完整定义（标识、描述与多段多角色文本）。
 *
 * <p>分段列表在构造时做防御性拷贝并转为不可变，供向量化匹配与按标识取回使用。
 */
@Data
public final class PromptDefinition {

    private final String id;
    private final String description;
    private final List<PromptSegment> segments;

    /**
     * 创建提示词定义。
     *
     * @param id          提示词标识，不可为空白
     * @param description 提示词描述，可为 null
     * @param segments    提示词分段列表，不可为 null 或空
     * @throws IllegalArgumentException id 为空白、segments 为 null / 空或含 null 元素时
     */
    public PromptDefinition(String id, String description, List<PromptSegment> segments) {
        if (blank(id) || segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("prompt definition is invalid");
        }
        List<PromptSegment> copied = new ArrayList<>(segments.size());
        for (PromptSegment segment : segments) {
            if (segment == null) {
                throw new IllegalArgumentException("prompt segment must not be null");
            }
            copied.add(segment);
        }
        this.id = id;
        this.description = description;
        this.segments = Collections.unmodifiableList(copied);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
