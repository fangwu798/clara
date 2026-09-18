package com.clara.model.api;

import com.clara.kernel.InvocationContext;
import com.clara.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 封装对话操作的输入参数。
 */
public final class ChatRequest {
    private final List<ChatMessage> messages;
    private final ModelParameters parameters;
    private final List<ToolSpecification> tools;
    private final ProviderOptions providerOptions;
    private final InvocationContext context;
    private final boolean clearTools;

    public ChatRequest(List<ChatMessage> messages, ModelParameters parameters, List<ToolSpecification> tools, ProviderOptions providerOptions, InvocationContext context) {
        this(messages, parameters, tools, false, providerOptions, context);
    }

    public ChatRequest(List<ChatMessage> messages, ModelParameters parameters, List<ToolSpecification> tools, boolean clearTools, ProviderOptions providerOptions, InvocationContext context) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (messages.contains(null)) {
            throw new IllegalArgumentException("messages must not contain null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        List<ToolSpecification> safeTools = tools == null ? Collections.<ToolSpecification>emptyList() : tools;
        if (safeTools.contains(null)) {
            throw new IllegalArgumentException("tools must not contain null");
        }
        this.messages = Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
        this.parameters = parameters;
        this.tools = Collections.unmodifiableList(new ArrayList<ToolSpecification>(safeTools));
        this.providerOptions = providerOptions;
        this.context = context;
        this.clearTools = clearTools;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public ModelParameters getParameters() {
        return parameters;
    }

    public List<ToolSpecification> getTools() {
        return tools;
    }

    public ProviderOptions getProviderOptions() {
        return providerOptions;
    }

    public InvocationContext getContext() {
        return context;
    }

    public boolean isClearTools() {
        return clearTools;
    }
}
