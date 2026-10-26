package com.clara.orchestration.context;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.core.memory.ISummarizeCoordinator;
import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.core.skill.IPromptProvider;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.dto.memory.TopicIsolationConfig;
import com.clara.dto.memory.WindowPolicy;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.orchestration.skill.SkillMatchCoordinator;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ContextAssembler} 单元测试：装配顺序、窗口淘汰 + 压缩并入 system、无 system 文本时不产生 SystemContent。
 */
public class ContextAssemblerTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void assemblePlacesSystemInstructionFirstWhenPromptMatched() {
        UserContent user = new UserContent("hi");
        FakeWindowMemory window = new FakeWindowMemory("c1", Collections.<IContent>singletonList(user));

        SkillMatchCoordinator skill = new SkillMatchCoordinator(
                text -> new float[] {1f, 0f},
                new RoutingStore(Collections.singletonList(
                        new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p1"), 0.9)),
                        Collections.<MemoryMatch>emptyList()),
                constantPromptProvider("SYS-PROMPT"));
        ContextAssembler assembler = new ContextAssembler(
                new WindowCompressionCoordinator(null),
                new SemanticRecallCoordinator(null, null),
                skill);

        AssembledContext assembled = assembler.assemble(window, control(false), null);

        List<IContent> messages = assembled.getMessages();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof SystemContent);
        assertEquals("SYS-PROMPT", ((SystemContent) messages.get(0)).instructionText());
        assertSame(user, messages.get(1));
        assertTrue(assembled.getTools().isEmpty());
    }

    @Test
    public void assembleMergesCompressionSummaryIntoSystemWhenWindowEvicts() {
        UserContent u1 = new UserContent("订单服务异常怎么排查");
        UserContent u2 = new UserContent("继续排查订单服务异常");
        UserContent u3 = new UserContent("订单服务异常的调用方有哪些");
        List<IContent> evicted = new ArrayList<>();
        ISummarizeCoordinator summarize = messages -> {
            evicted.addAll(messages);
            return new SystemContent("SUMMARY");
        };
        FakeWindowMemory window = new FakeWindowMemory("c1",
                Arrays.<IContent>asList(u1, u2, u3), Collections.<IContent>singletonList(u3));

        ContextAssembler assembler = new ContextAssembler(
                new WindowCompressionCoordinator(summarize),
                new SemanticRecallCoordinator(null, null),
                new SkillMatchCoordinator(null, null, null));

        AssembledContext assembled = assembler.assemble(window, control(true), null);

        assertEquals(Arrays.<IContent>asList(u1, u2), evicted);
        List<IContent> messages = assembled.getMessages();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof SystemContent);
        assertEquals("SUMMARY", ((SystemContent) messages.get(0)).instructionText());
        assertSame(u3, messages.get(1));
    }

    @Test
    public void assembleDropsPreviousTopicMessagesWhenTopicShifts() {
        UserContent a1 = new UserContent("订单服务异常怎么排查");
        AssistantContent a2 = new AssistantContent("先检查订单服务日志");
        UserContent b = new UserContent("今天天气怎么样");
        FakeWindowMemory window = new FakeWindowMemory("c1", Arrays.<IContent>asList(a1, a2, b));

        ContextAssembler assembler = topicAssembler();
        AssembledContext assembled = assembler.assemble(window, control(false), null);

        List<IContent> messages = assembled.getMessages();
        assertFalse(containsMessage(messages, a1));
        assertFalse(containsMessage(messages, a2));
        assertTrue(containsMessage(messages, b));
        assertTrue(systemText(assembled).contains("当前任务以最后一条用户消息为准"));
    }

    @Test
    public void assembleBringsBackExplicitlyReferencedTopic() {
        UserContent a1 = new UserContent("订单服务异常怎么排查");
        AssistantContent a2 = new AssistantContent("先检查订单服务日志");
        UserContent b1 = new UserContent("今天天气怎么样");
        AssistantContent b2 = new AssistantContent("今天晴");
        UserContent c = new UserContent("继续刚才订单服务的问题");
        FakeWindowMemory window = new FakeWindowMemory("c1",
                Arrays.<IContent>asList(a1, a2, b1, b2, c));

        AssembledContext assembled = topicAssembler().assemble(window, control(false), null);

        List<IContent> messages = assembled.getMessages();
        assertTrue(containsMessage(messages, a1));
        assertTrue(containsMessage(messages, a2));
        assertFalse(containsMessage(messages, b1));
        assertFalse(containsMessage(messages, b2));
        assertTrue(containsMessage(messages, c));
    }

    @Test
    public void assembleDoesNotSummarizeArchivedTopic() {
        UserContent a1 = new UserContent("订单服务异常怎么排查");
        UserContent b = new UserContent("今天天气怎么样");
        List<IContent> summarized = new ArrayList<>();
        ISummarizeCoordinator summarize = messages -> {
            summarized.addAll(messages);
            return new SystemContent("SUMMARY");
        };
        ContextAssembler assembler = new ContextAssembler(
                new WindowCompressionCoordinator(summarize),
                new SemanticRecallCoordinator(null, null),
                null,
                new SkillMatchCoordinator(null, null, null),
                null,
                TopicIsolationConfig.defaults(),
                null);
        FakeWindowMemory window = new FakeWindowMemory("c1", Arrays.<IContent>asList(a1, b),
                Collections.<IContent>singletonList(b));

        AssembledContext assembled = assembler.assemble(window, control(true), null);

        assertTrue(summarized.isEmpty());
        assertFalse(containsMessage(assembled.getMessages(), a1));
        assertTrue(containsMessage(assembled.getMessages(), b));
    }

    @Test
    public void assembleOmitsSystemContentWhenSystemTextEmpty() {
        UserContent user = new UserContent("hi");
        FakeWindowMemory window = new FakeWindowMemory("c1", Collections.<IContent>singletonList(user));

        ContextAssembler assembler = new ContextAssembler(
                new WindowCompressionCoordinator(null),
                new SemanticRecallCoordinator(null, null),
                new SkillMatchCoordinator(null, null, null));

        AssembledContext assembled = assembler.assemble(window, control(false), null);

        List<IContent> messages = assembled.getMessages();
        assertEquals(1, messages.size());
        for (IContent message : messages) {
            assertFalse(message instanceof SystemContent);
        }
        assertSame(user, messages.get(0));
    }

    @Test
    public void assembleDropsAssistantMessageWithBlankContentAndNoToolCalls() {
        AssistantContent invalid = new AssistantContent(null);
        UserContent user = new UserContent("q");
        FakeWindowMemory window = new FakeWindowMemory("c1", Arrays.<IContent>asList(invalid, user));

        ContextAssembler assembler = new ContextAssembler(
                new WindowCompressionCoordinator(null),
                new SemanticRecallCoordinator(null, null),
                new SkillMatchCoordinator(null, null, null));

        AssembledContext assembled = assembler.assemble(window, control(false), null);

        List<IContent> messages = assembled.getMessages();
        assertEquals(1, messages.size());
        assertSame(user, messages.get(0));
    }

    @Test
    public void assemblePrependsExtraPromptsAsSystemInstruction() {
        UserContent user = new UserContent("hi");
        FakeWindowMemory window = new FakeWindowMemory("c1", Collections.<IContent>singletonList(user));

        ContextAssembler assembler = new ContextAssembler(
                new WindowCompressionCoordinator(null),
                new SemanticRecallCoordinator(null, null),
                new SkillMatchCoordinator(null, null, null));

        AssembledContext assembled = assembler.assemble(window, control(false), null,
                Collections.singletonList("PLAN-PROMPT"));

        List<IContent> messages = assembled.getMessages();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof SystemContent);
        assertEquals("PLAN-PROMPT", ((SystemContent) messages.get(0)).instructionText());
        assertSame(user, messages.get(1));
    }
    @Test
    public void assembleRejectsNullWindowMemory() {
        ContextAssembler assembler = new ContextAssembler(
                new WindowCompressionCoordinator(null),
                new SemanticRecallCoordinator(null, null),
                new SkillMatchCoordinator(null, null, null));
        ContextControlConfig config = control(false);
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(null, config, null));
    }

    @Test
    public void assembleInjectsBasePromptsEvenWhenNoPromptMatched() {
        UserContent user = new UserContent("hi");
        FakeWindowMemory window = new FakeWindowMemory("c1", Collections.<IContent>singletonList(user));

        ContextAssembler assembler = new ContextAssembler(
                new WindowCompressionCoordinator(null),
                new SemanticRecallCoordinator(null, null),
                null,
                new SkillMatchCoordinator(null, null, null),
                Collections.singletonList("BASE-PROMPT"));

        AssembledContext assembled = assembler.assemble(window, control(false), null);

        List<IContent> messages = assembled.getMessages();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof SystemContent);
        assertEquals("BASE-PROMPT", ((SystemContent) messages.get(0)).instructionText());
        assertSame(user, messages.get(1));
    }

    @Test
    public void assemblePlacesBasePromptsBeforeMatchedPrompts() {
        UserContent user = new UserContent("hi");
        FakeWindowMemory window = new FakeWindowMemory("c1", Collections.<IContent>singletonList(user));

        SkillMatchCoordinator skill = new SkillMatchCoordinator(
                text -> new float[] {1f, 0f},
                new RoutingStore(Collections.singletonList(
                        new MemoryMatch(entry(MemorySourceTypeEnum.PROMPT, "p1"), 0.9)),
                        Collections.<MemoryMatch>emptyList()),
                constantPromptProvider("SYS-PROMPT"));
        ContextAssembler assembler = new ContextAssembler(
                new WindowCompressionCoordinator(null),
                new SemanticRecallCoordinator(null, null),
                null,
                skill,
                Collections.singletonList("BASE-PROMPT"));

        AssembledContext assembled = assembler.assemble(window, control(false), null);

        assertEquals("BASE-PROMPT\nSYS-PROMPT",
                ((SystemContent) assembled.getMessages().get(0)).instructionText());
    }

    // ---- helpers -----------------------------------------------------------

    private static ContextControlConfig control(boolean compressEnabled) {
        return ContextControlConfig.builder()
                .contextWindowTokens(-1L).windowMaxMessages(-1).windowMaxSizeBytes(-1L)
                .windowMaxTokensPercent(80).compressEnabled(compressEnabled)
                .retrievalCount(3).similarityThreshold(0.5).build();
    }

    private static ContextAssembler topicAssembler() {
        return new ContextAssembler(
                new WindowCompressionCoordinator(null),
                new SemanticRecallCoordinator(null, null),
                null,
                new SkillMatchCoordinator(null, null, null),
                null,
                TopicIsolationConfig.defaults(),
                null);
    }

    private static boolean containsMessage(List<IContent> messages, IContent target) {
        for (IContent message : messages) {
            if (message == target) {
                return true;
            }
        }
        return false;
    }

    private static String systemText(AssembledContext assembled) {
        for (IContent message : assembled.getMessages()) {
            if (message instanceof SystemContent) {
                return ((SystemContent) message).instructionText();
            }
        }
        return "";
    }

    private static MemoryEntry entry(MemorySourceTypeEnum sourceType, String sourceId) {
        return MemoryEntry.builder("id-" + sourceId, sourceType, sourceId, "desc-" + sourceId, HASH,
                new float[] {1f, 0f}).build();
    }

    private static IPromptProvider constantPromptProvider(final String text) {
        return new IPromptProvider() {
            @Override
            public String prompt(String promptId) {
                return text;
            }

            @Override
            public String summarizePrompt() {
                return "";
            }
        };
    }

    private static final class FakeWindowMemory implements IWindowMemory {

        private final String conversationId;
        private final List<IContent> history;
        private final List<IContent> windowed;

        FakeWindowMemory(String conversationId, List<IContent> history) {
            this(conversationId, history, history);
        }

        FakeWindowMemory(String conversationId, List<IContent> history, List<IContent> windowed) {
            this.conversationId = conversationId;
            this.history = new ArrayList<>(history);
            this.windowed = new ArrayList<>(windowed);
        }

        @Override
        public String conversationId() {
            return conversationId;
        }

        @Override
        public void append(List<IContent> messages) {
            history.addAll(messages);
        }

        @Override
        public List<IContent> messages() {
            return new ArrayList<>(history);
        }

        @Override
        public List<IContent> window(WindowPolicy policy, ITokenCountEstimator estimator) {
            return new ArrayList<>(windowed);
        }

        @Override
        public void clear() {
            history.clear();
        }
    }

    private static final class RoutingStore implements ISemanticMemoryStore {

        private final List<MemoryMatch> promptMatches;
        private final List<MemoryMatch> toolMatches;

        RoutingStore(List<MemoryMatch> promptMatches, List<MemoryMatch> toolMatches) {
            this.promptMatches = promptMatches;
            this.toolMatches = toolMatches;
        }

        @Override
        public void upsert(List<MemoryEntry> entries) {
        }

        @Override
        public List<MemoryMatch> search(SemanticMemoryQuery query) {
            return query.getSourceType() == MemorySourceTypeEnum.TOOL_DESCRIPTION
                    ? toolMatches : promptMatches;
        }

        @Override
        public void deleteById(String entryId) {
        }

        @Override
        public void deleteByConversation(String conversationId) {
        }

        @Override
        public void close() {
        }
    }
}
