package com.clara.orchestration.interaction;

/**
 * 表示。
 */
public final class InteractionOption {
    private final String optionId;
    private final String label;
    public InteractionOption(String optionId, String label) {
        if (blank(optionId) || blank(label)) { throw new IllegalArgumentException("option fields must not be blank"); }
        this.optionId = optionId; this.label = label;
    }
    public String getOptionId() { return optionId; }
    public String getLabel() { return label; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
