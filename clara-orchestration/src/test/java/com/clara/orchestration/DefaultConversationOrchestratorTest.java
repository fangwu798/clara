package com.clara.orchestration;

import com.clara.core.message.IContent;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.message.delta.UserVisionContent;
import com.clara.orchestration.api.ConversationRequest;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link DefaultConversationOrchestrator} 用户消息装配的单元测试：图片附件随能力种类走不同通道。
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

    private static ConversationRequest request(List<String> attachments) {
        return ConversationRequest.builder()
                .conversationId("c1")
                .model("m1")
                .userInput("hi")
                .attachments(attachments)
                .build();
    }
}
