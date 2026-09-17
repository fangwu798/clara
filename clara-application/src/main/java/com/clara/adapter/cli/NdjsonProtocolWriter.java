package com.clara.adapter.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 写入NDJSON协议数据。
 */
final class NdjsonProtocolWriter {
    private final ObjectMapper json;
    private final PrintStream out;
    private final int maxBytes;
    private long sequence;

    NdjsonProtocolWriter(ObjectMapper json, PrintStream out, int maxBytes) {
        this.json = json;
        this.out = out;
        this.maxBytes = maxBytes;
    }

    void event(String type, String runId, String interactionId, Object payload) {
        long value = ++sequence;
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("protocolVersion", Integer.valueOf(1));
        envelope.put("eventId", runId + "-" + value);
        envelope.put("sequence", Long.valueOf(value));
        envelope.put("timestamp", Long.valueOf(System.currentTimeMillis()));
        envelope.put("type", type);
        envelope.put("runId", runId);
        if (interactionId != null) envelope.put("interactionId", interactionId);
        envelope.put("payload", payload);
        try {
            String line = json.writeValueAsString(envelope);
            if (line.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new IllegalArgumentException("protocol line exceeds limit");
            }
            out.println(line);
            out.flush();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
