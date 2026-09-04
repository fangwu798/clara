package com.clara.model.provider.compatible;

import com.clara.model.api.ChatResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class OpenAiCompatibleStreamChunk {
    private final String textDelta;
    private final String reasoningDelta;
    private final List<ToolDelta> toolDeltas;
    private final ChatResponse response;

    OpenAiCompatibleStreamChunk(String textDelta, String reasoningDelta,
            List<ToolDelta> toolDeltas, ChatResponse response) {
        this.textDelta = textDelta;
        this.reasoningDelta = reasoningDelta;
        this.toolDeltas = toolDeltas == null ? Collections.<ToolDelta>emptyList()
                : Collections.unmodifiableList(new ArrayList<ToolDelta>(toolDeltas));
        this.response = response;
    }

    String getTextDelta() { return textDelta; }
    String getReasoningDelta() { return reasoningDelta; }
    List<ToolDelta> getToolDeltas() { return toolDeltas; }
    ChatResponse getResponse() { return response; }
    boolean isComplete() { return response != null; }

    static final class ToolDelta {
        private final int index;
        private final String id;
        private final String name;
        private final String argumentsDelta;

        ToolDelta(int index, String id, String name, String argumentsDelta) {
            this.index = index;
            this.id = id;
            this.name = name;
            this.argumentsDelta = argumentsDelta;
        }

        int getIndex() { return index; }
        String getId() { return id; }
        String getName() { return name; }
        String getArgumentsDelta() { return argumentsDelta; }
    }
}
