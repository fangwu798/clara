package com.clara.model.runtime;

import com.clara.model.api.ModelProvider;

/**
 * 唯一标识模型。
 */
final class ModelKey {
    private final ModelProvider provider;
    private final String alias;
    ModelKey(ModelProvider provider, String alias) {
        this.provider = provider;
        this.alias = alias;
    }
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ModelKey)) return false;
        ModelKey key = (ModelKey) other;
        return provider == key.provider && alias.equals(key.alias);
    }
    @Override
    public int hashCode() {
        return 31 * provider.hashCode() + alias.hashCode();
    }
}
