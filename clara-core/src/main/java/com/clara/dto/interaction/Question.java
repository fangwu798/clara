package com.clara.dto.interaction;

import com.clara.utils.StringUtils;
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
     * 由构建器创建问题并完成校验与防御性复制。
     *
     * @param builder 已填充的构建器，不能为 null
     * @throws IllegalArgumentException 必填字段非法、选项 ID 重复或选择题缺少选项时
     */
    private Question(Builder builder) {
        if (builder.type == null || StringUtils.isBlank(builder.text) || StringUtils.isBlank(builder.scopeKey) || builder.maxTextLength <= 0) {
            throw new IllegalArgumentException("question fields are invalid");
        }
        List<InteractionOption> copied = copyOptions(builder.options);
        if (isSelection(builder.type) && copied.isEmpty()) {
            throw new IllegalArgumentException("selection question requires options");
        }
        this.type = builder.type;
        this.text = builder.text;
        this.options = copied;
        this.answerSchema = builder.answerSchema;
        this.defaultAnswer = copyDefaultAnswer(builder.defaultAnswer);
        this.required = builder.required;
        this.scopeKey = builder.scopeKey;
        this.maxTextLength = builder.maxTextLength;
    }

    /**
     * 创建问题构建器。
     *
     * @param type 问题类型，不能为 null
     * @param text 问题文本，不能为空白
     * @return 问题构建器，非 null
     */
    public static Builder builder(InteractionTypeEnum type, String text) {
        return new Builder(type, text);
    }

    private static List<InteractionOption> copyOptions(List<InteractionOption> options) {
        List<InteractionOption> copied = options == null ? Collections.emptyList() : new ArrayList<>(options);
        Set<String> ids = new HashSet<>();
        for (InteractionOption option : copied) {
            if (option == null || !ids.add(option.getOptionId())) {
                throw new IllegalArgumentException("question option IDs must be unique");
            }
        }
        return Collections.unmodifiableList(copied);
    }

    private static Map<String, Object> copyDefaultAnswer(Map<String, Object> answer) {
        return answer == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(answer));
    }

    private static boolean isSelection(InteractionTypeEnum type) {
        return type == InteractionTypeEnum.SINGLE_SELECT || type == InteractionTypeEnum.MULTI_SELECT;
    }

    /**
     * 问题构建器；必填项为类型、文本、scopeKey 与 maxTextLength。
     */
    public static final class Builder {

        private final InteractionTypeEnum type;
        private final String text;
        private List<InteractionOption> options;
        private String answerSchema;
        private Map<String, Object> defaultAnswer;
        private boolean required;
        private String scopeKey;
        private int maxTextLength;

        private Builder(InteractionTypeEnum type, String text) {
            this.type = type;
            this.text = text;
        }

        public Builder options(List<InteractionOption> options) {
            this.options = options;
            return this;
        }

        public Builder answerSchema(String answerSchema) {
            this.answerSchema = answerSchema;
            return this;
        }

        public Builder defaultAnswer(Map<String, Object> defaultAnswer) {
            this.defaultAnswer = defaultAnswer;
            return this;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder scopeKey(String scopeKey) {
            this.scopeKey = scopeKey;
            return this;
        }

        public Builder maxTextLength(int maxTextLength) {
            this.maxTextLength = maxTextLength;
            return this;
        }

        public Question build() {
            return new Question(this);
        }
    }
}
