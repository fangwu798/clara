package com.clara.orchestration.interaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

public final class Question {
    private final InteractionType type;
    private final String text;
    private final List<InteractionOption> options;
    private final String answerSchema;
    private final Map<String, Object> defaultAnswer;
    private final boolean required;
    private final String scopeKey;
    private final int maxTextLength;
    public Question(InteractionType type, String text, List<InteractionOption> options,
            String answerSchema, Map<String, Object> defaultAnswer, boolean required,
            String scopeKey, int maxTextLength) {
        if (type == null || blank(text) || blank(scopeKey) || maxTextLength <= 0) {
            throw new IllegalArgumentException("question fields are invalid");
        }
        List<InteractionOption> copied = options == null ? Collections.<InteractionOption>emptyList()
                : new ArrayList<InteractionOption>(options);
        Set<String> ids = new HashSet<String>();
        for (InteractionOption option : copied) {
            if (option == null || !ids.add(option.getOptionId())) {
                throw new IllegalArgumentException("question option IDs must be unique");
            }
        }
        if ((type == InteractionType.SINGLE_SELECT || type == InteractionType.MULTI_SELECT)
                && copied.isEmpty()) throw new IllegalArgumentException("selection question requires options");
        this.type = type; this.text = text;
        this.options = Collections.unmodifiableList(copied); this.answerSchema = answerSchema;
        this.defaultAnswer = defaultAnswer == null ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(defaultAnswer));
        this.required = required; this.scopeKey = scopeKey; this.maxTextLength = maxTextLength;
    }
    public InteractionType getType() { return type; }
    public String getText() { return text; }
    public List<InteractionOption> getOptions() { return options; }
    public String getAnswerSchema() { return answerSchema; }
    public Map<String, Object> getDefaultAnswer() { return defaultAnswer; }
    public boolean isRequired() { return required; }
    public String getScopeKey() { return scopeKey; }
    public int getMaxTextLength() { return maxTextLength; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
