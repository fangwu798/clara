package com.clara.model.api;

import com.clara.kernel.InvocationContext;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public final class EmbeddingRequest {
    private final List<String> texts;
    private final InvocationContext context;
    public EmbeddingRequest(List<String> texts, InvocationContext context) {
        if (texts == null || texts.isEmpty()) throw new IllegalArgumentException("texts must not be empty");
        if (texts.contains(null)) throw new IllegalArgumentException("texts must not contain null");
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.texts = Collections.unmodifiableList(new ArrayList<String>(texts));
        this.context = context;
    }
    public List<String> getTexts() { return texts; }
    public InvocationContext getContext() { return context; }
}
