package com.clara.dto.skill;

import com.clara.utils.StringUtils;
import lombok.Data;

/**
 * 提示词分段：提示词定义中的一段文本及其角色。
 */
@Data
public final class PromptSegment {

    private final String role;
    private final String content;

    /**
     * 创建提示词分段。
     *
     * @param role    分段角色（如 system / user / assistant），不可为空白
     * @param content 分段文本，不可为空白
     * @throws IllegalArgumentException role 或 content 为空白时
     */
    public PromptSegment(String role, String content) {
        if (StringUtils.isBlank(role) || StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("prompt segment is invalid");
        }
        this.role = role;
        this.content = content;
    }

}
