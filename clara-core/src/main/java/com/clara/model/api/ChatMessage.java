package com.clara.model.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 表示对话交换的消息。
 */
public final class ChatMessage {
    private final Role role;
    private final List<Content> contents;
    private final String name;
    private final Map<String, String> metadata;

    public ChatMessage(Role role, List<Content> contents, String name, Map<String, String> metadata) {
        if (role == null) { throw new IllegalArgumentException("role must not be null"); }
        if (contents == null || contents.isEmpty()) {
            throw new IllegalArgumentException("contents must not be empty");
        }
        if (contents.contains(null)) { throw new IllegalArgumentException("contents must not contain null"); }
        this.role = role;
        this.contents = Collections.unmodifiableList(new ArrayList<Content>(contents));
        this.name = name;
        this.metadata = metadata == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
    }
    public Role getRole() { return role; }
    public List<Content> getContents() { return contents; }
    public String getName() { return name; }
    public Map<String, String> getMetadata() { return metadata; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ChatMessage)) return false;
        ChatMessage that = (ChatMessage) other;
        return role == that.role && contents.equals(that.contents)
                && Objects.equals(name, that.name) && metadata.equals(that.metadata);
    }
    @Override public int hashCode() { return Objects.hash(role, contents, name, metadata); }
}
