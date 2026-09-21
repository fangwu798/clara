package com.clara.adapter.rest;

import com.clara.kernel.exception.ClaraError;
import com.clara.orchestration.interaction.InteractionRequest;
import com.clara.workspace.application.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在集成边界之间适配工作区运行SSE。
 */
public final class WorkspaceRunSseAdapter {
    private final ObjectMapper json;
    private final AtomicLong sequence = new AtomicLong();

    public WorkspaceRunSseAdapter(ObjectMapper json) {
        if (json == null) {
            throw new IllegalArgumentException();
        }
        this.json = json;
    }

    public List<SseEvent> events(WorkspaceRunResult result) {
        List<SseEvent> events = new ArrayList<SseEvent>();
        if (result.getStatus() == WorkspaceRunStatus.WAITING_INPUT) {
            for (InteractionRequest interaction : result.getInteractions())
                events.add(event(result.getRunId(), interaction.getInteractionId(), "interaction.required", interaction));
        } else events.add(event(result.getRunId(), null, "run.completed", result));
        return Collections.unmodifiableList(events);
    }

    public SseEvent error(String runId, ClaraError error) {
        return event(runId, null, "error", error);
    }

    private SseEvent event(String run, String interactionId, String type, Object payload) {
        long value = sequence.incrementAndGet();
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("protocolVersion", Integer.valueOf(1));
        envelope.put("eventId", run + "-" + value);
        envelope.put("sequence", Long.valueOf(value));
        envelope.put("timestamp", Long.valueOf(System.currentTimeMillis()));
        envelope.put("type", type);
        envelope.put("runId", run);
        if (interactionId != null) envelope.put("interactionId", interactionId);
        envelope.put("payload", payload);
        try {
            return new SseEvent(run + "-" + value, type, json.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
