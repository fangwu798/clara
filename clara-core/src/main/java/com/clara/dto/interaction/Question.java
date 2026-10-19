package com.clara.dto.interaction;

import com.clara.enums.interaction.InteractionTypeEnum;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 交互问题：需要用户回答的问题（类型、文本、选项、Schema 与默认答案等）。
 *
 * <p>构造时校验选项唯一性；单选 / 多选必须至少提供一个选项。
 */
@Data
public final class Question {

    private final InteractionTypeEnum type;
    private final String text;
    private final List<InteractionOption> options;
    private final String answerSchema;
    private final Map<String, Object> defaultAnswer;
    private final boolean required;
    private final String scopeKey;
    private final int maxTextLength;

    /**
     * 创建交互问题。
     *
     * @param type          交互类型，不可为 null
     * @param text          问题文本，不可为空白
     * @param options       选项列表，可为 null（非选择类问题）
     * @param answerSchema  答案 Schema，可为 null
     * @param defaultAnswer 默认答案，可为 null
     * @param required      是否必答
     * @param scopeKey      作用域键，不可为空白
     * @param maxTextLength 文本最大长度，必须为正
     * @throws IllegalArgumentException 任一字段不合法、选项 ID 重复或选择类问题缺少选项时
     */
    public Question(InteractionTypeEnum type, String text, List<InteractionOption> options,
            String answerSchema, Map<String, Object> defaultAnswer, boolean required,
            String scopeKey, int maxTextLength) {
        if (type == null || blank(text) || blank(scopeKey) || maxTextLength <= 0) {
            throw new IllegalArgumentException("question fields are invalid");
        }
        List<InteractionOption> copied = options == null ? Collections.<InteractionOption>emptyList()
                : new ArrayList<>(options);
        Set<String> ids = new HashSet<>();
        for (InteractionOption option : copied) {
            if (option == null || !ids.add(option.getOptionId())) {
                throw new IllegalArgumentException("question option IDs must be unique");
            }
        }
        if ((type == InteractionTypeEnum.SINGLE_SELECT || type == InteractionTypeEnum.MULTI_SELECT)
                && copied.isEmpty()) {
            throw new IllegalArgumentException("selection question requires options");
        }
        this.type = type;
        this.text = text;
        this.options = Collections.unmodifiableList(copied);
        this.answerSchema = answerSchema;
        this.defaultAnswer = defaultAnswer == null ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(defaultAnswer));
        this.required = required;
        this.scopeKey = scopeKey;
        this.maxTextLength = maxTextLength;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
