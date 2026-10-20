package com.clara.mcp.client;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 封装MCP的分页数据。
 */
@Getter
public final class McpPage<T> {
    private final List<T> items;
    private final String nextCursor;

    public McpPage(List<T> items, String nextCursor) {
        if (items == null) { throw new IllegalArgumentException("items must not be null"); }
        if (items.contains(null)) { throw new IllegalArgumentException("items must not contain null"); }
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.nextCursor = normalize(nextCursor);
    }

    private static String normalize(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
