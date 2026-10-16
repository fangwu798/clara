package com.clara.orchestration;

import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.dto.memory.WindowPolicy;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.message.delta.UserVisionContent;
import com.clara.orchestration.api.ConversationRequest;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link DefaultConversationOrchestrator} 用户消息的单元测试：图片附件随能力种类走不同通道，
 * 且同一轮被重发（模型故障转移）时不会重复追加用户消息。
 */
public class DefaultConversationOrchestratorTest {

    @Test
    public void usesVisionContentForChatWithAttachments() {
        IContent content = DefaultConversationOrchestrator.userContent(
                request(Arrays.asList("https://example.com/a.png")), CapabilityKindEnum.CHAT);

        assertTrue(content instanceof UserVisionContent);
        assertEquals(1, ((UserVisionContent) content).getImages().size());
    }

    @Test
    public void keepsPlainTextWhenChatHasNoAttachments() {
        IContent content = DefaultConversationOrchestrator.userContent(request(null), CapabilityKindEnum.CHAT);

        assertTrue(content instanceof UserContent);
    }

    @Test
    public void keepsPlainTextForImageCapability() {
        // 图片生成能力下附件是参考图（由请求 image 字段承载），不应进入对话消息
        IContent content = DefaultConversationOrchestrator.userContent(
                request(Arrays.asList("data:image/png;base64,AAAA")), CapabilityKindEnum.IMAGE);

        assertTrue(content instanceof UserContent);
    }

    @Test
    public void appendsUserTurnWhenHistoryIsEmpty() {
        assertTrue(DefaultConversationOrchestrator.shouldAppendUserTurn(memory(), new UserContent("hi")));
    }

    @Test
    public void appendsUserTurnWhenLastMessageIsAssistant() {
        assertTrue(DefaultConversationOrchestrator.shouldAppendUserTurn(
                memory(new AssistantContent("hello")), new UserContent("hi")));
    }

    @Test
    public void skipsUserTurnAlreadyAtHistoryTail() {
        // 模型故障转移把同一轮换个模型重发：末尾已是同一条用户消息，不再追加，避免提示词里出现两遍
        UserContent turn = new UserContent("hi");
        assertFalse(DefaultConversationOrchestrator.shouldAppendUserTurn(memory(turn), turn));
    }

    @Test
    public void appendsUserTurnWhenTailIsDifferentInput() {
        assertTrue(DefaultConversationOrchestrator.shouldAppendUserTurn(
                memory(new UserContent("hi")), new UserContent("hello")));
    }

    private static IWindowMemory memory(IContent... messages) {
        final List<IContent> history = new ArrayList<IContent>(Arrays.asList(messages));
        return new IWindowMemory() {
            @Override
            public String conversationId() {
                return "c1";
            }

            @Override
            public void append(List<IContent> items) {
                history.addAll(items);
            }

            @Override
            public List<IContent> messages() {
                return history;
            }

            @Override
            public List<IContent> window(WindowPolicy policy, ITokenCountEstimator estimator) {
                return history;
            }

            @Override
            public void clear() {
                history.clear();
            }
        };
    }

    private static ConversationRequest request(List<String> attachments) {
        return ConversationRequest.builder()
                .conversationId("c1")
                .model("m1")
                .userInput("hi")
                .attachments(attachments)
                .build();
    }
}
