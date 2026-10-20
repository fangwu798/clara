package com.clara.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Codex turn metadata headers and client metadata extraction.
 */
public class CodexTuiGeneratorTest {

    @Test
    public void buildsCompleteHeaderSet() throws Exception {
        CodexTuiGenerator generator = new CodexTuiGenerator();

        Map<String, String> headers = generator.buildHeaders();

        assertEquals("codex-tui", headers.get("originator"));
        assertNotNull(headers.get("user-agent"));
        assertEquals("remote_compaction_v2", headers.get("x-codex-beta-features"));
        assertEquals("true", headers.get("x-openai-internal-codex-responses-lite"));
        assertEquals(headers.get("session-id"), generator.getSessionId());
        assertEquals(headers.get("thread-id"), generator.getThreadId());
        assertEquals(generator.getWindowId(), headers.get("x-codex-window-id"));
        assertNotNull(headers.get("x-client-request-id"));

        JsonNode metadata = new ObjectMapper().readTree(headers.get("x-codex-turn-metadata"));
        assertEquals(headers.get("session-id"), metadata.get("session_id").asText());
        assertEquals(headers.get("thread-id"), metadata.get("thread_id").asText());
        assertEquals("/root", metadata.get("agent_name").asText());
        assertEquals("turn", metadata.get("request_kind").asText());
        assertEquals("workspace-write", metadata.get("sandbox_mode").asText());
        assertTrue(generator.getWindowId().endsWith(":0"));
    }

    @Test
    public void extractsClientMetadataFromHeaders() {
        Map<String, String> headers = new CodexTuiGenerator().buildHeaders();

        Map<String, String> metadata = CodexTuiGenerator.buildClientMetadata(headers);

        assertEquals(headers.get("thread-id"), metadata.get("thread_id"));
        assertEquals(headers.get("session-id"), metadata.get("session_id"));
        assertEquals(headers.get("x-codex-window-id"), metadata.get("x-codex-window-id"));
        assertNotNull(metadata.get("x-codex-installation-id"));
        assertNotNull(metadata.get("turn_id"));
        assertNotNull(metadata.get("root_turn_id"));
        assertEquals(headers.get("x-codex-turn-metadata"), metadata.get("x-codex-turn-metadata"));
    }

    @Test
    public void returnsEmptyMetadataWhenHeaderIsMissingOrBlank() {
        assertTrue(CodexTuiGenerator.buildClientMetadata(null).isEmpty());
        assertTrue(CodexTuiGenerator.buildClientMetadata(Collections.<String, String>emptyMap()).isEmpty());
        Map<String, String> blank = new HashMap<>();
        blank.put("x-codex-turn-metadata", "");
        assertTrue(CodexTuiGenerator.buildClientMetadata(blank).isEmpty());
    }

    @Test
    public void keepsOtherFieldsWhenTurnMetadataIsNotJson() {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-codex-turn-metadata", "not-json");
        headers.put("thread-id", "t-1");
        headers.put("session-id", "s-1");

        Map<String, String> metadata = CodexTuiGenerator.buildClientMetadata(headers);

        assertEquals("t-1", metadata.get("thread_id"));
        assertEquals("s-1", metadata.get("session_id"));
        assertEquals("not-json", metadata.get("x-codex-turn-metadata"));
        assertFalse(metadata.containsKey("turn_id"));
        assertFalse(metadata.containsKey("x-codex-installation-id"));
    }

    @Test
    public void ignoresNonTextualTurnFields() {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-codex-turn-metadata", "{\"turn_id\":1,\"root_turn_id\":{\"a\":1}}");

        Map<String, String> metadata = CodexTuiGenerator.buildClientMetadata(headers);

        assertFalse(metadata.containsKey("turn_id"));
        assertFalse(metadata.containsKey("root_turn_id"));
    }
}
