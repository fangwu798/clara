package com.clara.model.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ModelApiImmutabilityTest {
    @Test
    public void chatMessageCopiesInputCollections() {
        List<Content> contents = new ArrayList<Content>();
        contents.add(new TextContent("hello"));
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("source", "test");

        ChatMessage message = new ChatMessage(Role.USER, contents, null, metadata);
        contents.clear();
        metadata.clear();

        assertEquals(1, message.getContents().size());
        assertEquals("test", message.getMetadata().get("source"));
        assertEquals("text", message.getContents().get(0).getType());
    }

    @Test
    public void embeddingResponseCopiesVectorsOnInputAndOutput() {
        float[] vector = new float[] { 1.0f, 2.0f };
        EmbeddingResponse response = new EmbeddingResponse(Arrays.asList(vector));
        vector[0] = 9.0f;
        List<float[]> returned = response.getEmbeddings();
        returned.get(0)[1] = 8.0f;

        assertEquals(1.0f, response.getEmbeddings().get(0)[0], 0.0f);
        assertEquals(2.0f, response.getEmbeddings().get(0)[1], 0.0f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void chatRequestRequiresInvocationContext() {
        ChatMessage message = new ChatMessage(Role.USER,
                Arrays.<Content>asList(new TextContent("hello")), null,
                Collections.<String, String>emptyMap());
        new ChatRequest(Arrays.asList(message), null,
                Collections.<com.clara.tool.ToolSpecification>emptyList(), null, null);
    }
}
