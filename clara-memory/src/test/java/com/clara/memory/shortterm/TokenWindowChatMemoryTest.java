package com.clara.memory.shortterm;

import com.clara.memory.api.ChatMemory;
import com.clara.memory.api.TokenCountEstimator;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.Content;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import com.clara.model.api.ToolCallContent;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TokenWindowChatMemoryTest {
    private final TokenCountEstimator estimator = new MetadataTokenEstimator();

    @Test
    public void preservesSystemAndEvictsToolExchangeTogether() {
        ChatMemory memory = TokenWindowChatMemory.withMaxTokens(5, estimator);
        ChatMessage system = message(Role.SYSTEM, "system", 2);
        ChatMessage assistant = toolCall("call-1", 2);
        ChatMessage result = message(Role.TOOL, "result", 1);
        ChatMessage latest = message(Role.USER, "latest", 2);

        memory.add(system);
        memory.add(message(Role.USER, "old", 2));
        memory.add(assistant);
        memory.add(result);
        assertEquals(Arrays.asList(system, assistant, result), memory.messages());

        memory.add(latest);
        assertEquals(Arrays.asList(system, latest), memory.messages());
    }

    @Test
    public void dynamicBudgetIsAppliedOnRead() {
        final int[] maximum = { 6 };
        TokenWindowChatMemory memory = TokenWindowChatMemory.builder()
                .dynamicMaxTokens(id -> maximum[0], estimator).build();
        memory.add(message(Role.USER, "one", 2));
        memory.add(message(Role.ASSISTANT, "two", 2));
        memory.add(message(Role.USER, "three", 2));
        maximum[0] = 4;
        assertEquals(2, memory.messages().size());
    }

    private static ChatMessage message(Role role, String text, int tokens) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("tokens", String.valueOf(tokens));
        return new ChatMessage(role, Arrays.<Content>asList(new TextContent(text)), null, metadata);
    }

    private static ChatMessage toolCall(String id, int tokens) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("tokens", String.valueOf(tokens));
        return new ChatMessage(Role.ASSISTANT,
                Arrays.<Content>asList(new ToolCallContent(id, "search", "{}")), null, metadata);
    }

    private static final class MetadataTokenEstimator implements TokenCountEstimator {
        public int estimateTokenCountInMessage(ChatMessage message) {
            return Integer.parseInt(message.getMetadata().get("tokens"));
        }
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            int total = 0;
            for (ChatMessage message : messages) total += estimateTokenCountInMessage(message);
            return total;
        }
    }
}
