package com.clara.model.runtime;

/**
 * 定义模型元数据来源的可选取值。
 */
public enum ModelMetadataSource {
    PROVIDER_CATALOG(0),
    AUDITED_STATIC(1),
    STARTUP_PROBE(2),
    CONTRACT_TEST(3);

    private final int priority;

    ModelMetadataSource(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }
}
