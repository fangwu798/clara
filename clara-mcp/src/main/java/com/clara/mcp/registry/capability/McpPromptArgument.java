package com.clara.mcp.registry.capability;

import lombok.Getter;

/**
 * 表示一个 MCP 提示词参数：名称、描述与是否必填。
 *
 * <p>不可变值对象 + 构造期校验。
 */
@Getter
public final class McpPromptArgument {
    private final String name;
    private final String description;
    private final boolean required;

    public McpPromptArgument(String name, String description, boolean required) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.description = description;
        this.required = required;
    }
}
