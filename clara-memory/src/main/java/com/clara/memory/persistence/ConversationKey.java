package com.clara.memory.persistence;

/**
 * 会话历史单元的定位键：由会话标识、供应商与模型共同组成。
 *
 * <p>历史按「供应商 + 模型」隔离（业务决策 D1），因此持久化键必须同时携带三者，
 * 同一会话内不同模型各自持有一份独立历史。本类是不可变值对象。
 */
public final class ConversationKey {

    private final String conversationId;
    private final String provider;
    private final String model;

    /**
     * 创建会话历史单元键。
     *
     * @param conversationId 会话标识，不可为空白
     * @param provider       供应商标识，可为 null（视为空串）
     * @param model          模型标识，可为 null（视为空串）
     * @throws IllegalArgumentException conversationId 为 null 或空白时
     */
    public ConversationKey(String conversationId, String provider, String model) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        this.conversationId = conversationId;
        this.provider = provider == null ? "" : provider;
        this.model = model == null ? "" : model;
    }

    public String conversationId() {
        return conversationId;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    /**
     * 稳定的字符串键，作为内存映射键与文件名的输入。
     *
     * @return 会话、供应商、模型以 NUL 分隔拼接后的稳定字符串，非 null
     */
    public String value() {
        return conversationId + "\u0000" + provider + "\u0000" + model;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConversationKey)) {
            return false;
        }
        ConversationKey that = (ConversationKey) other;
        return conversationId.equals(that.conversationId)
                && provider.equals(that.provider)
                && model.equals(that.model);
    }

    @Override
    public int hashCode() {
        int result = conversationId.hashCode();
        result = 31 * result + provider.hashCode();
        result = 31 * result + model.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return value();
    }
}
