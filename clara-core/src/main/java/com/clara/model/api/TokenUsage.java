package com.clara.model.api;

/**
 * 表示。
 */
public final class TokenUsage {
    private final int inputTokens;
    private final int outputTokens;
    public TokenUsage(int inputTokens, int outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public int getTotalTokens() { return inputTokens + outputTokens; }
}
