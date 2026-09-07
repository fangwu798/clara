package com.clara.mcp.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 封装MCP的分页数据。
 */
public final class McpPage<T> {
    private final List<T> items;
    private final String nextCursor;

    public McpPage(List<T> items, String nextCursor) {
        if (items == null) { throw new IllegalArgumentException("items must not be null"); }
        if (items.contains(null)) { throw new IllegalArgumentException("items must not contain null"); }
        this.items = Collections.unmodifiableList(new ArrayList<T>(items));
        this.nextCursor = normalize(nextCursor);
    }
    public List<T> getItems() { return items; }
    public String getNextCursor() { return nextCursor; }
    private static String normalize(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
