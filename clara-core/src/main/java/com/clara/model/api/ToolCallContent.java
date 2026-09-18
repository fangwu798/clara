package com.clara.model.api;

import lombok.Data;

import java.util.Objects;

/**
 * 表示工具调用携带的内容。
 */
@Data
public final class ToolCallContent implements Content {
    private final String id;
    private final String name;
    private final String arguments;

    public ToolCallContent(String id, String name, String arguments) {
        if (isBlank(id)) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (isBlank(name)) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (arguments == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getType() {
        return "tool_call";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ToolCallContent)) return false;
        ToolCallContent that = (ToolCallContent) other;
        return id.equals(that.id) && name.equals(that.name) && arguments.equals(that.arguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, arguments);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
