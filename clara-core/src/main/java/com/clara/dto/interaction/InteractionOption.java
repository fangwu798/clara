package com.clara.dto.interaction;

import com.clara.utils.StringUtils;
import lombok.Data;

/**
 * 交互选项：选择类问题的单个选项（选项 ID + 标签）。
 */
@Data
public final class InteractionOption {

    private final String optionId;
    private final String label;

    /**
     * 创建交互选项。
     *
     * @param optionId 选项标识，不可为空白
     * @param label    选项标签，不可为空白
     * @throws IllegalArgumentException optionId 或 label 为空白时
     */
    public InteractionOption(String optionId, String label) {
        if (StringUtils.isBlank(optionId) || StringUtils.isBlank(label)) {
            throw new IllegalArgumentException("option fields must not be blank");
        }
        this.optionId = optionId;
        this.label = label;
    }

}
