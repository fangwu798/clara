package com.clara.model.api;

import java.util.Objects;

public final class TextContent implements Content {
    private final String text;
    public TextContent(String text) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        this.text = text;
    }
    public String getType() { return "text"; }
    public String getText() { return text; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof TextContent
                && text.equals(((TextContent) other).text);
    }
    @Override public int hashCode() { return Objects.hash(text); }
}
