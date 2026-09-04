package com.clara.memory.shortterm;

import com.clara.memory.api.ChatMemory;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.Content;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import com.clara.model.api.ToolCallContent;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MessageWindowChatMemoryTest {
    @Test
    public void keepsOneSystemMessageAndEvictsToolExchangeAsAGroup() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(3).alwaysKeepSystemMessageFirst(true).build();
        ChatMessage system = text(Role.SYSTEM, "rules");
        ChatMessage replacement = text(Role.SYSTEM, "new rules");
        ChatMessage assistantCall = toolCall("call-1");
        ChatMessage toolResult = toolResult("call-1", "result");

        memory.add(system);
        memory.add(system);
        memory.add(text(Role.USER, "question"));
        memory.add(assistantCall);
        memory.add(toolResult);
        assertEquals(Arrays.asList(system, assistantCall, toolResult), memory.messages());

        memory.add(text(Role.USER, "next"));
        assertEquals(Arrays.asList(system, text(Role.USER, "next")), memory.messages());

        memory.add(replacement);
        assertEquals(replacement, memory.messages().get(0));
        assertEquals(1, countRole(memory.messages(), Role.SYSTEM));
    }

    @Test
    public void dynamicWindowShrinksWhenRead() {
        final int[] maximum = { 3 };
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .dynamicMaxMessages(id -> maximum[0]).build();
        memory.add(text(Role.USER, "one"));
        memory.add(text(Role.ASSISTANT, "two"));
        memory.add(text(Role.USER, "three"));
        maximum[0] = 2;
        assertEquals(Arrays.asList(text(Role.ASSISTANT, "two"), text(Role.USER, "three")),
                memory.messages());
    }

    @Test
    public void setKeepsLastSystemAtItsRelativePosition() {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(5).build();
        ChatMessage user = text(Role.USER, "question");
        ChatMessage oldSystem = text(Role.SYSTEM, "old rules");
        ChatMessage answer = text(Role.ASSISTANT, "answer");
        ChatMessage currentSystem = text(Role.SYSTEM, "current rules");
        memory.set(Arrays.asList(user, oldSystem, answer, currentSystem));

        assertEquals(Arrays.asList(user, answer, currentSystem), memory.messages());
    }

    @Test
    public void sharedStoreDoesNotLoseConcurrentUpdates() throws Exception {
        final InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        final ChatMemory first = MessageWindowChatMemory.builder().id("shared")
                .maxMessages(300).chatMemoryStore(store).build();
        final ChatMemory second = MessageWindowChatMemory.builder().id("shared")
                .maxMessages(300).chatMemoryStore(store).build();
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread a = writer(first, "a", start, failure);
        Thread b = writer(second, "b", start, failure);
        a.start(); b.start(); start.countDown(); a.join(); b.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(200, first.messages().size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void returnedMessagesAreImmutable() {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(2);
        memory.add(text(Role.USER, "one"));
        memory.messages().clear();
    }

    private static Thread writer(final ChatMemory memory, final String prefix,
            final CountDownLatch start, final AtomicReference<Throwable> failure) {
        return new Thread(new Runnable() {
            public void run() {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) memory.add(text(Role.USER, prefix + i));
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            }
        });
    }

    private static ChatMessage text(Role role, String text) {
        return new ChatMessage(role, Arrays.<Content>asList(new TextContent(text)), null,
                Collections.<String, String>emptyMap());
    }

    private static ChatMessage toolCall(String id) {
        return new ChatMessage(Role.ASSISTANT,
                Arrays.<Content>asList(new ToolCallContent(id, "search", "{}")), null,
                Collections.<String, String>emptyMap());
    }

    private static ChatMessage toolResult(String id, String result) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("toolCallId", id);
        return new ChatMessage(Role.TOOL, Arrays.<Content>asList(new TextContent(result)),
                "search", metadata);
    }

    private static int countRole(List<ChatMessage> messages, Role role) {
        int count = 0;
        for (ChatMessage message : messages) if (message.getRole() == role) count++;
        return count;
    }
}
